package com.triples.rougether.userapi.routine.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
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
class RoutineLogServiceDailyRewardCapIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

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

    private RoutineLogService routineLogService;
    private Long userId;

    @BeforeEach
    void setUp() {
        DailyRewardService dailyRewardService = new DailyRewardService(routineLogRepository,
                todoRepository);
        routineLogService = new RoutineLogService(routineRepository, routineLogRepository,
                userWalletRepository, streakRepository, dailyRewardService,
                new TransactionTemplate(transactionManager));
        User user = userRepository.save(User.signUp());
        userId = user.getId();
        persistWallet(user, 0);
    }

    @Test
    void 루틴_5건_완료로_50코인을_소진하면_6번째는_0_지급된다() {
        // 10코인 × 5건 = 상한 50 소진
        for (int i = 0; i < 5; i++) {
            Long routineId = persistRoutine(userId);
            RoutineLogResponse response = routineLogService.complete(userId, routineId,
                    new RoutineLogCreateRequest(null));
            assertThat(response.rewardAmount()).isEqualTo(10);
        }
        assertThat(walletBalance()).isEqualTo(50);

        // 6번째 루틴은 0 지급
        Long sixthRoutine = persistRoutine(userId);
        RoutineLogResponse sixthResponse = routineLogService.complete(userId, sixthRoutine,
                new RoutineLogCreateRequest(null));
        assertThat(sixthResponse.rewardAmount()).isEqualTo(0);
        assertThat(walletBalance()).isEqualTo(50);
    }

    @Test
    void 상한_소진_완료_취소_후_재완료하면_다시_지급된다() {
        // 50코인 소진
        Long[] routines = new Long[6];
        for (int i = 0; i < 5; i++) {
            routines[i] = persistRoutine(userId);
            routineLogService.complete(userId, routines[i], new RoutineLogCreateRequest(null));
        }
        assertThat(walletBalance()).isEqualTo(50);

        // 6번째: 0 지급
        routines[5] = persistRoutine(userId);
        RoutineLogResponse sixthResponse = routineLogService.complete(userId, routines[5],
                new RoutineLogCreateRequest(null));
        assertThat(sixthResponse.rewardAmount()).isEqualTo(0);

        // 5번째 취소 → 한도 10 복구
        routineLogService.cancel(userId, routines[4], TODAY);
        assertThat(walletBalance()).isEqualTo(40);

        // 6번째를 다시 완료하려면 재완료 guard 때문에 안 됨. 대신 다른 루틴으로 테스트
        Long seventhRoutine = persistRoutine(userId);
        RoutineLogResponse seventhResponse = routineLogService.complete(userId, seventhRoutine,
                new RoutineLogCreateRequest(null));
        assertThat(seventhResponse.rewardAmount()).isEqualTo(10);
        assertThat(walletBalance()).isEqualTo(50);
    }

    @Test
    void 상한_소진_후_완료는_0_지급이므로_취소해도_지갑_불변() {
        // 50코인 소진
        for (int i = 0; i < 5; i++) {
            Long routineId = persistRoutine(userId);
            routineLogService.complete(userId, routineId, new RoutineLogCreateRequest(null));
        }
        assertThat(walletBalance()).isEqualTo(50);

        // 6번째: 0 지급
        Long sixthRoutine = persistRoutine(userId);
        RoutineLogResponse sixthResponse = routineLogService.complete(userId, sixthRoutine,
                new RoutineLogCreateRequest(null));
        assertThat(sixthResponse.rewardAmount()).isEqualTo(0);

        // 6번째 취소: reward_amount=0이므로 지갑 불변
        routineLogService.cancel(userId, sixthRoutine, TODAY);
        assertThat(walletBalance()).isEqualTo(50);
    }

    @Test
    void 남은_한도가_정가보다_적으면_남은_만큼만_부분_지급된다() {
        // 45코인 소진 상태를 만듦 — 10×4건 + 정책 변경 전 5코인으로 기록된 이력 1건
        for (int i = 0; i < 4; i++) {
            Long routineId = persistRoutine(userId);
            routineLogService.complete(userId, routineId, new RoutineLogCreateRequest(null));
        }
        persistLegacyRoutineLogRewardedWith(5);
        assertThat(walletBalance()).isEqualTo(40);

        // 잔여 5 < 정가 10 → 5만 지급
        Long partialRoutine = persistRoutine(userId);
        RoutineLogResponse partial = routineLogService.complete(userId, partialRoutine,
                new RoutineLogCreateRequest(null));
        assertThat(partial.rewardAmount()).isEqualTo(5);
        assertThat(walletBalance()).isEqualTo(45);

        // 상한 소진 → 다음 완료는 0
        Long nextRoutine = persistRoutine(userId);
        RoutineLogResponse next = routineLogService.complete(userId, nextRoutine,
                new RoutineLogCreateRequest(null));
        assertThat(next.rewardAmount()).isEqualTo(0);
        assertThat(walletBalance()).isEqualTo(45);
    }

    @Test
    void 부분_지급_건을_취소하면_지급액만큼만_환불되고_한도도_그만큼만_복구된다() {
        for (int i = 0; i < 4; i++) {
            Long routineId = persistRoutine(userId);
            routineLogService.complete(userId, routineId, new RoutineLogCreateRequest(null));
        }
        persistLegacyRoutineLogRewardedWith(5);

        Long partialRoutine = persistRoutine(userId);
        assertThat(routineLogService.complete(userId, partialRoutine,
                new RoutineLogCreateRequest(null)).rewardAmount()).isEqualTo(5);
        assertThat(walletBalance()).isEqualTo(45);

        // 취소: 정가 10이 아니라 실지급 5만 회수
        routineLogService.cancel(userId, partialRoutine, TODAY);
        assertThat(walletBalance()).isEqualTo(40);

        // 복구된 한도도 5뿐이므로 다음 완료 역시 5만 지급
        Long nextRoutine = persistRoutine(userId);
        RoutineLogResponse next = routineLogService.complete(userId, nextRoutine,
                new RoutineLogCreateRequest(null));
        assertThat(next.rewardAmount()).isEqualTo(5);
        assertThat(walletBalance()).isEqualTo(45);
    }

    @Test
    void 과거_날짜_완료는_0_지급이고_상한_집계에도_잡히지_않는다() {
        Long pastRoutine = persistRoutine(userId);
        RoutineLogResponse past = routineLogService.complete(userId, pastRoutine,
                new RoutineLogCreateRequest(TODAY.minusDays(1)));
        assertThat(past.rewardAmount()).isEqualTo(0);
        assertThat(walletBalance()).isEqualTo(0);

        // 과거 완료가 한도를 깎지 않았으므로 오늘 5건 모두 정가 지급
        for (int i = 0; i < 5; i++) {
            Long routineId = persistRoutine(userId);
            assertThat(routineLogService.complete(userId, routineId,
                    new RoutineLogCreateRequest(null)).rewardAmount()).isEqualTo(10);
        }
        assertThat(walletBalance()).isEqualTo(50);
    }

    // 지급액이 10의 배수가 아닌 이력(정책 변경 전 5코인 투두 등)을 흉내내 부분 지급 경계를 만듦.
    // 지갑에는 더하지 않음 — 이 테스트가 보려는 건 잔여 한도 계산이지 잔액 이력이 아님
    private void persistLegacyRoutineLogRewardedWith(int rewardAmount) {
        Routine routine = routineRepository.findById(persistRoutine(userId)).orElseThrow();
        routineLogRepository.save(RoutineLog.complete(
                routine, TODAY, Instant.now(), CurrencyType.COIN, rewardAmount));
    }

    private Long persistRoutine(Long userId) {
        User user = userRepository.getReferenceById(userId);
        Routine routine = Routine.create(user, null, "루틴명", AuthType.CHECK,
                null, null, null, null, null);
        return routineRepository.save(routine).getId();
    }

    private void persistWallet(User user, int balance) {
        UserWallet wallet = BeanUtils.instantiateClass(UserWallet.class);
        ReflectionTestUtils.setField(wallet, "user", user);
        ReflectionTestUtils.setField(wallet, "currencyType", CurrencyType.COIN);
        ReflectionTestUtils.setField(wallet, "balance", balance);
        userWalletRepository.save(wallet);
    }

    private long walletBalance() {
        return userWalletRepository.findByUserIdAndCurrencyType(userId, CurrencyType.COIN)
                .orElseThrow().getBalance();
    }
}
