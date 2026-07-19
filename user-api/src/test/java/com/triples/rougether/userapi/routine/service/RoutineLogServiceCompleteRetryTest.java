package com.triples.rougether.userapi.routine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.routine.dto.RoutineLogCreateRequest;
import com.triples.rougether.userapi.routine.dto.RoutineLogResponse;
import com.triples.rougether.userapi.routine.reward.service.DailyRewardService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// day-end 배치가 로그 부재 확인과 insert 사이에 FAILED를 먼저 커밋하는 경합은
// 실제 커밋 타이밍 재현이 어려워 단위 수준으로 검증 - unique 충돌 시 새 트랜잭션 재시도가 전이 경로로 흡수하는지
class RoutineLogServiceCompleteRetryTest {

    private static final LocalDate YESTERDAY = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);
    private static final Long USER_ID = 7L;
    private static final Long ROUTINE_ID = 11L;

    private final RoutineRepository routineRepository = mock(RoutineRepository.class);
    private final RoutineLogRepository routineLogRepository = mock(RoutineLogRepository.class);
    private final UserWalletRepository userWalletRepository = mock(UserWalletRepository.class);
    private final StreakRepository streakRepository = mock(StreakRepository.class);
    private final DailyRewardService dailyRewardService = mock(DailyRewardService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    private final RoutineLogService service = new RoutineLogService(routineRepository,
            routineLogRepository, userWalletRepository, streakRepository, dailyRewardService,
            new TransactionTemplate(transactionManager));

    @Test
    void 배치가_먼저_FAILED를_커밋해_unique_충돌이_나면_재시도에서_전이로_흡수한다() {
        Routine routine = mock(Routine.class);
        when(routine.getId()).thenReturn(ROUTINE_ID);
        when(routine.getOriginRoutineId()).thenReturn(ROUTINE_ID);
        RoutineLog failedLog = RoutineLog.fail(routine, YESTERDAY);

        when(userWalletRepository.findWithLockByUserIdAndCurrencyType(USER_ID, CurrencyType.COIN))
                .thenReturn(Optional.of(mock(UserWallet.class)));
        when(routineRepository.findByIdAndUserIdAndDeletedAtIsNull(ROUTINE_ID, USER_ID))
                .thenReturn(Optional.of(routine));
        when(routineLogRepository.findByLineageAndDateAndStatus(
                ROUTINE_ID, YESTERDAY, RoutineLogStatus.COMPLETED)).thenReturn(List.of());
        // 1차: FAILED 부재 → insert 시도 → 배치 커밋과 충돌 / 2차: 배치가 만든 FAILED가 보임
        when(routineLogRepository.findByLineageAndDateAndStatus(
                ROUTINE_ID, YESTERDAY, RoutineLogStatus.FAILED))
                .thenReturn(List.of(), List.of(failedLog));
        when(routineLogRepository.save(any(RoutineLog.class)))
                .thenThrow(new DataIntegrityViolationException("uq_routine_log_routine_date"));
        when(streakRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        RoutineLogResponse response = service.complete(USER_ID, ROUTINE_ID,
                new RoutineLogCreateRequest(YESTERDAY));

        assertThat(response.status()).isEqualTo(RoutineLogStatus.COMPLETED);
        assertThat(response.rewardAmount()).isZero();
        assertThat(response.rewardCurrencyType()).isEqualTo(CurrencyType.COIN);
        assertThat(failedLog.getStatus()).isEqualTo(RoutineLogStatus.COMPLETED);
        // insert는 1차에서만 시도되고, 1차 트랜잭션은 롤백·2차는 커밋됨
        verify(routineLogRepository, times(1)).save(any(RoutineLog.class));
        verify(transactionManager, times(1)).rollback(any());
        verify(transactionManager, times(1)).commit(any());
    }
}
