package com.triples.rougether.userapi.routine.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.Streak;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.routine.dto.RoutineLogCreateRequest;
import com.triples.rougether.userapi.routine.dto.RoutineLogResponse;
import com.triples.rougether.userapi.routine.dto.StreakSummaryResponse;
import com.triples.rougether.userapi.routine.error.RoutineErrorCode;
import com.triples.rougether.userapi.routine.error.RoutineLogErrorCode;
import com.triples.rougether.userapi.routine.reward.service.DailyRewardService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class RoutineLogService {

    // KST 고정 — "당일" 판정과 routineDate 기본값 모두 이 기준임
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final CurrencyType REWARD_CURRENCY = CurrencyType.COIN;
    private static final int REWARD_AMOUNT = 10;

    private final RoutineRepository routineRepository;
    private final RoutineLogRepository routineLogRepository;
    private final UserWalletRepository userWalletRepository;
    private final StreakRepository streakRepository;
    private final DailyRewardService dailyRewardService;
    private final TransactionTemplate transactionTemplate;

    // 완료 체크: routine_logs + user_wallets + streaks 3개 테이블을 한 트랜잭션으로 변경함.
    // @Transactional 대신 template인 이유: unique 충돌 재시도가 롤백된 첫 트랜잭션 밖에서 새로 시작돼야 함
    public RoutineLogResponse complete(Long userId, Long routineId, RoutineLogCreateRequest request) {
        try {
            return transactionTemplate.execute(tx -> doComplete(userId, routineId, request));
        } catch (DataIntegrityViolationException e) {
            // 로그 부재 확인과 insert 사이에 day-end 배치가 같은 (routine_id, routine_date)의 FAILED를
            // 먼저 커밋한 경합 — 재시도에서는 그 row가 보여 FAILED 전이(또는 중복 거부) 경로로 흡수됨
            return transactionTemplate.execute(tx -> doComplete(userId, routineId, request));
        }
    }

    private RoutineLogResponse doComplete(Long userId, Long routineId, RoutineLogCreateRequest request) {
        // 지갑 행 락을 트랜잭션 첫 조회로 선점함 — MySQL REPEATABLE_READ에서 스냅샷이 락 획득 뒤에 잡혀야
        // 동시 완료(루틴·투두)의 상한 카운트가 서로의 커밋을 보고 직렬화됨. 락 이전에 일반 SELECT를 두면 안 됨
        UserWallet wallet = findWalletForUpdate(userId);
        Routine routine = findOwnedRoutine(userId, routineId);

        LocalDate today = LocalDate.now(KST);
        LocalDate routineDate = request.routineDate() != null ? request.routineDate() : today;
        // 과거 완료는 허용, 미래만 거부. 단 코인·스트릭은 당일 완료에만 반응함
        if (routineDate.isAfter(today)) {
            throw new BusinessException(RoutineLogErrorCode.INVALID_ROUTINE_DATE);
        }
        boolean isToday = routineDate.equals(today);
        // 앱 레이어 중복 완료 guard(DB unique와 이중 방어). 계보 기준 — FAILED 전이·버전 분기 전 완료는
        // 다른 버전 id의 row로 남아 있어 현재 id만 보면 같은 날짜를 이중 완료할 수 있음
        if (!routineLogRepository.findByLineageAndDateAndStatus(
                lineageKey(routine), routineDate, RoutineLogStatus.COMPLETED).isEmpty()) {
            throw new BusinessException(RoutineLogErrorCode.ALREADY_COMPLETED);
        }

        RoutineLog failedLog = findFailedLogInLineage(routine, routineDate);
        if (failedLog != null) {
            failedLog.completeFromFailed(Instant.now(), REWARD_CURRENCY);
            Streak currentStreak = streakRepository.findByUserId(userId).orElse(null);
            return RoutineLogResponse.from(failedLog, currentStreak);
        }

        // 이 완료가 그 유저의 오늘 첫 완료인지(스트릭은 첫 완료에만 반응)
        boolean firstToday = isToday && routineLogRepository.countByRoutine_UserIdAndRoutineDateAndStatus(
                userId, today, RoutineLogStatus.COMPLETED) == 0;

        // 과거 완료는 reward_amount=0으로 기록해 취소 시 환불도 0이 되게 함
        int reward = isToday && dailyRewardService.canReward(userId, today) ? REWARD_AMOUNT : 0;

        RoutineLog log = routineLogRepository.save(RoutineLog.complete(
                routine, routineDate, Instant.now(), REWARD_CURRENCY, reward));

        if (reward > 0) {
            wallet.add(reward);
        }

        Streak streak = isToday
                ? updateStreakOnComplete(routine, today, firstToday)
                : streakRepository.findByUserId(userId).orElse(null);
        return RoutineLogResponse.from(log, streak);
    }

    // 완료 취소: 코인 차감 + log hard delete + 스트릭 롤백을 한 트랜잭션으로 처리함.
    // logId 대신 클라가 보는 날짜를 받음 — 다른 날짜 취소가 실수로 오늘 완료를 건드리지 않게 함
    @Transactional
    public StreakSummaryResponse cancel(Long userId, Long routineId, LocalDate date) {
        Routine routine = findOwnedRoutine(userId, routineId); // 소유권 guard(타인·미존재·삭제 → ROUTINE_NOT_FOUND)

        LocalDate today = LocalDate.now(KST);
        // 과거 완료도 취소 가능(미래만 거부). 환불은 log.reward_amount라 과거 완료 취소는 0 환불임
        if (date.isAfter(today)) {
            throw new BusinessException(RoutineLogErrorCode.LOG_NOT_CANCELABLE);
        }
        // 버전 분기 전 완료·FAILED 전이 완료는 옛 버전 id의 row로 남으므로 계보까지 찾아야 취소 가능함.
        // 계보의 모든 버전이 같은 user 소유라 위 guard로 소유권은 이미 보장됨
        RoutineLog log = routineLogRepository
                .findByRoutineIdAndRoutineDateAndStatus(routineId, date, RoutineLogStatus.COMPLETED)
                .or(() -> routineLogRepository
                        .findByLineageAndDateAndStatus(lineageKey(routine), date, RoutineLogStatus.COMPLETED)
                        .stream().findFirst())
                .orElseThrow(() -> new BusinessException(RoutineLogErrorCode.ROUTINE_LOG_NOT_FOUND));

        UserWallet wallet = findWalletForUpdate(userId);
        // 음수 잔액 허용 — 회수 정책 확정 전 임시로, 잔액이 보상액보다 적어도 그대로 차감함
        wallet.subtract(log.getRewardAmount());

        routineLogRepository.delete(log);
        routineLogRepository.flush(); // 아래 count가 삭제를 반영해야 함

        // 스트릭은 당일 완료 취소에만 반응함(과거 완료는 애초에 스트릭에 반영되지 않음)
        Streak streak = date.equals(today)
                ? rollbackStreakOnCancel(userId, today)
                : streakRepository.findByUserId(userId).orElse(null);
        return streak != null
                ? StreakSummaryResponse.from(streak)
                : new StreakSummaryResponse(0, 0, null);
    }

    private Streak updateStreakOnComplete(Routine routine, LocalDate today, boolean firstToday) {
        Optional<Streak> existing = streakRepository.findByUserId(routine.getUser().getId());
        if (!firstToday) {
            // 오늘 이미 완료가 있었으면 streak이 존재함(그날 성공은 한 번만 반영)
            return existing.orElseGet(() -> streakRepository.save(Streak.start(routine.getUser(), today)));
        }
        if (existing.isPresent()) {
            existing.get().applySuccess(today);
            return existing.get();
        }
        return streakRepository.save(Streak.start(routine.getUser(), today));
    }

    private Streak rollbackStreakOnCancel(Long userId, LocalDate today) {
        Streak streak = streakRepository.findByUserId(userId).orElse(null);
        if (streak == null) {
            return null;
        }
        long remainingToday = routineLogRepository.countByRoutine_UserIdAndRoutineDateAndStatus(
                userId, today, RoutineLogStatus.COMPLETED);
        // 오늘 완료가 0이 되고 마지막 성공일이 오늘일 때만 롤백(다른 완료가 남으면 그날은 여전히 성공일)
        if (remainingToday == 0 && today.equals(streak.getLastSuccessDate())) {
            streak.rollback(today);
        }
        return streak;
    }

    // 계보(origin) 안에서 해당 날짜의 FAILED row
    private RoutineLog findFailedLogInLineage(Routine routine, LocalDate routineDate) {
        return routineLogRepository
                .findByLineageAndDateAndStatus(lineageKey(routine), routineDate, RoutineLogStatus.FAILED)
                .stream().findFirst().orElse(null);
    }

    // coalesce 키와 짝 — origin 미백필 row는 자기 id가 계보 키임
    private Long lineageKey(Routine routine) {
        return routine.getOriginRoutineId() != null ? routine.getOriginRoutineId() : routine.getId();
    }

    private Routine findOwnedRoutine(Long userId, Long routineId) {
        return routineRepository.findByIdAndUserIdAndDeletedAtIsNull(routineId, userId)
                .orElseThrow(() -> new BusinessException(RoutineErrorCode.ROUTINE_NOT_FOUND));
    }

    private UserWallet findWalletForUpdate(Long userId) {
        return userWalletRepository.findWithLockByUserIdAndCurrencyType(userId, REWARD_CURRENCY)
                .orElseThrow(() -> new BusinessException(RoutineLogErrorCode.WALLET_NOT_FOUND));
    }
}
