package com.triples.rougether.userapi.routine.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.Streak;
import com.triples.rougether.domain.routine.entity.StreakStatus;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.routine.dto.RoutineLogCreateRequest;
import com.triples.rougether.userapi.routine.dto.RoutineLogResponse;
import com.triples.rougether.userapi.routine.reward.service.DailyRewardService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class RoutineFailedLogTransitionIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private RoutineLogRepository routineLogRepository;
    @Autowired
    private UserWalletRepository userWalletRepository;
    @Autowired
    private StreakRepository streakRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TodoRepository todoRepository;

@Autowired
    private PlatformTransactionManager transactionManager;

    private RoutineLogService service;
    private User user;
    private Long userId;
    private Long routineId;

    @BeforeEach
    void setUp() {
        service = new RoutineLogService(routineRepository, routineLogRepository,
                userWalletRepository, streakRepository,
                new DailyRewardService(routineLogRepository, todoRepository),
                new TransactionTemplate(transactionManager));
        user = userRepository.save(User.signUp());
        userId = user.getId();
        routineId = persistRoutine(user).getId();
        persistWallet(user, 50);
    }

    @Test
    void FAILED_row가_있으면_insert_대신_그_row를_COMPLETED로_전이한다() {
        Long failedLogId = persistFailedLog(routineId, YESTERDAY);
        persistStreak(userId, 3, YESTERDAY);

        RoutineLogResponse response = service.complete(userId, routineId,
                new RoutineLogCreateRequest(YESTERDAY));

        // 새 row가 아니라 기존 FAILED row가 갱신됨(unique 충돌 없음)
        assertThat(response.id()).isEqualTo(failedLogId);
        assertThat(routineLogRepository.findByRoutineIdAndRoutineDate(routineId, YESTERDAY)).hasSize(1);
        RoutineLog transitioned = routineLogRepository.findById(failedLogId).orElseThrow();
        assertThat(transitioned.getStatus()).isEqualTo(RoutineLogStatus.COMPLETED);
        assertThat(transitioned.getCompletedAt()).isNotNull();
        // 과거 완료 규칙 그대로 — 보상 0·지갑 불변·스트릭 불변. 통화는 일반 완료 경로와 같게 COIN 기록
        assertThat(transitioned.getRewardAmount()).isZero();
        assertThat(transitioned.getRewardCurrencyType()).isEqualTo(CurrencyType.COIN);
        assertThat(response.rewardCurrencyType()).isEqualTo(CurrencyType.COIN);
        assertThat(walletBalance()).isEqualTo(50);
        assertThat(response.streak().currentCount()).isEqualTo(3);
        assertThat(streakRepository.findByUserId(userId).orElseThrow().getCurrentCount()).isEqualTo(3);
    }

    @Test
    void 버전_분기된_루틴은_옛_버전에_남은_FAILED_row를_전이한다() {
        // FAILED는 그날 유효했던 옛 버전에 남고, 유저는 현재(새) 버전 id로 완료를 호출함
        Long failedLogId = persistFailedLog(routineId, YESTERDAY);
        Routine oldVersion = routineRepository.findById(routineId).orElseThrow();
        Routine newVersion = routineRepository.save(oldVersion.copyAsNewVersion(
                null, "새 버전", null, null, null, null, null, null));
        oldVersion.softDelete(Instant.now());
        routineRepository.save(oldVersion);

        RoutineLogResponse response = service.complete(userId, newVersion.getId(),
                new RoutineLogCreateRequest(YESTERDAY));

        assertThat(response.id()).isEqualTo(failedLogId);
        assertThat(routineLogRepository.findById(failedLogId).orElseThrow().getStatus())
                .isEqualTo(RoutineLogStatus.COMPLETED);
        // 새 버전 쪽에 중복 row가 생기지 않음
        assertThat(routineLogRepository.findByRoutineIdAndRoutineDate(newVersion.getId(), YESTERDAY))
                .isEmpty();
    }

    @Test
    void 전이된_완료를_취소하면_row가_hard_delete되고_환불은_0이다() {
        persistFailedLog(routineId, YESTERDAY);
        service.complete(userId, routineId, new RoutineLogCreateRequest(YESTERDAY));

        service.cancel(userId, routineId, YESTERDAY);

        // 기존 정책대로 그 날짜 로그는 사라지고(배치는 재생성하지 않음) 지갑은 그대로
        assertThat(routineLogRepository.findByRoutineIdAndRoutineDate(routineId, YESTERDAY)).isEmpty();
        assertThat(walletBalance()).isEqualTo(50);
    }

    @Test
    void 이미_COMPLETED인_로그는_전이할_수_없다() {
        Routine routine = routineRepository.findById(routineId).orElseThrow();
        RoutineLog completed = routineLogRepository.save(
                RoutineLog.complete(routine, YESTERDAY, Instant.now(), CurrencyType.COIN, 0));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> completed.completeFromFailed(Instant.now(), CurrencyType.COIN))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 버전_분기_후_전이된_완료를_새_버전_id로_취소할_수_있다() {
        // 전이된 COMPLETED row는 옛 버전 id에 남지만, 유저가 아는 id는 새 버전임
        persistFailedLog(routineId, YESTERDAY);
        Routine newVersion = forkVersion(routineId);
        service.complete(userId, newVersion.getId(), new RoutineLogCreateRequest(YESTERDAY));

        service.cancel(userId, newVersion.getId(), YESTERDAY);

        assertThat(routineLogRepository.findByRoutineIdAndRoutineDate(routineId, YESTERDAY)).isEmpty();
        assertThat(routineLogRepository.findByRoutineIdAndRoutineDate(newVersion.getId(), YESTERDAY)).isEmpty();
        assertThat(walletBalance()).isEqualTo(50);
    }

    @Test
    void 계보에_전이된_완료가_있으면_새_버전_id의_재완료는_거부된다() {
        persistFailedLog(routineId, YESTERDAY);
        Routine newVersion = forkVersion(routineId);
        service.complete(userId, newVersion.getId(), new RoutineLogCreateRequest(YESTERDAY));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.complete(userId, newVersion.getId(), new RoutineLogCreateRequest(YESTERDAY)))
                .isInstanceOf(com.triples.rougether.common.error.BusinessException.class);
        // 새 버전 쪽에 중복 row가 생기지 않았어야 함
        assertThat(routineLogRepository.findByRoutineIdAndRoutineDate(newVersion.getId(), YESTERDAY)).isEmpty();
    }

    @Test
    void FAILED_row가_없는_과거_완료는_기존대로_새_row를_insert한다() {
        RoutineLogResponse response = service.complete(userId, routineId,
                new RoutineLogCreateRequest(YESTERDAY));

        assertThat(response.status()).isEqualTo(RoutineLogStatus.COMPLETED);
        assertThat(routineLogRepository.findByRoutineIdAndRoutineDate(routineId, YESTERDAY)).hasSize(1);
    }

    // oldId를 닫고 같은 계보의 새 버전을 만듦
    private Routine forkVersion(Long oldId) {
        Routine oldVersion = routineRepository.findById(oldId).orElseThrow();
        Routine newVersion = routineRepository.save(oldVersion.copyAsNewVersion(
                null, "새 버전", null, null, null, null, null, null));
        oldVersion.softDelete(Instant.now());
        routineRepository.save(oldVersion);
        return newVersion;
    }

    private Routine persistRoutine(User owner) {
        Routine routine = routineRepository.save(Routine.create(owner, null, "아침 운동",
                AuthType.CHECK, "DAILY", null, null, null, null));
        routine.assignOriginToSelf();
        return routine;
    }

    private Long persistFailedLog(Long targetRoutineId, LocalDate date) {
        Routine routine = routineRepository.findById(targetRoutineId).orElseThrow();
        return routineLogRepository.save(RoutineLog.fail(routine, date)).getId();
    }

    private void persistWallet(User owner, int balance) {
        UserWallet wallet = BeanUtils.instantiateClass(UserWallet.class);
        ReflectionTestUtils.setField(wallet, "user", owner);
        ReflectionTestUtils.setField(wallet, "currencyType", CurrencyType.COIN);
        ReflectionTestUtils.setField(wallet, "balance", balance);
        userWalletRepository.save(wallet);
    }

    private void persistStreak(Long ownerId, int currentCount, LocalDate lastSuccessDate) {
        User owner = userRepository.findById(ownerId).orElseThrow();
        Streak streak = Streak.start(owner, lastSuccessDate);
        ReflectionTestUtils.setField(streak, "currentCount", currentCount);
        ReflectionTestUtils.setField(streak, "longestCount", currentCount);
        ReflectionTestUtils.setField(streak, "status", StreakStatus.ACTIVE);
        streakRepository.save(streak);
    }

    private int walletBalance() {
        return userWalletRepository.findByUserIdAndCurrencyType(userId, CurrencyType.COIN)
                .orElseThrow().getBalance();
    }
}
