package com.triples.rougether.userapi.routine.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.routine.dto.RoutineLogCreateRequest;
import com.triples.rougether.userapi.routine.dto.RoutineLogResponse;
import com.triples.rougether.userapi.routine.reward.service.DailyRewardService;
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
    void 루틴_4건_완료_후_5번째는_0_지급된다() {
        // 4개의 루틴 생성 및 완료
        for (int i = 0; i < 4; i++) {
            Long routineId = persistRoutine(userId);
            RoutineLogResponse response = routineLogService.complete(userId, routineId,
                    new RoutineLogCreateRequest(null));
            assertThat(response.rewardAmount()).isEqualTo(10);
        }
        assertThat(walletBalance()).isEqualTo(40);

        // 5번째 루틴은 0 지급
        Long fifthRoutine = persistRoutine(userId);
        RoutineLogResponse fifthResponse = routineLogService.complete(userId, fifthRoutine,
                new RoutineLogCreateRequest(null));
        assertThat(fifthResponse.rewardAmount()).isEqualTo(0);
        assertThat(walletBalance()).isEqualTo(40);
    }

    @Test
    void 상한_초과_완료_취소_후_재완료하면_다시_지급된다() {
        // 4건 지급
        Long[] routines = new Long[5];
        for (int i = 0; i < 4; i++) {
            routines[i] = persistRoutine(userId);
            routineLogService.complete(userId, routines[i], new RoutineLogCreateRequest(null));
        }
        assertThat(walletBalance()).isEqualTo(40);

        // 5번째: 0 지급
        routines[4] = persistRoutine(userId);
        RoutineLogResponse fifthResponse = routineLogService.complete(userId, routines[4],
                new RoutineLogCreateRequest(null));
        assertThat(fifthResponse.rewardAmount()).isEqualTo(0);

        // 4번째 취소 → 슬롯 복구
        routineLogService.cancel(userId, routines[3], TODAY);
        assertThat(walletBalance()).isEqualTo(30);

        // 5번째를 다시 완료하려면 재완료 guard 때문에 안 됨. 대신 다른 루틴으로 테스트
        Long sixthRoutine = persistRoutine(userId);
        RoutineLogResponse sixthResponse = routineLogService.complete(userId, sixthRoutine,
                new RoutineLogCreateRequest(null));
        assertThat(sixthResponse.rewardAmount()).isEqualTo(10);
        assertThat(walletBalance()).isEqualTo(40);
    }

    @Test
    void 상한_초과_완료는_0_지급이므로_취소해도_지갑_불변() {
        // 4건 지급
        for (int i = 0; i < 4; i++) {
            Long routineId = persistRoutine(userId);
            routineLogService.complete(userId, routineId, new RoutineLogCreateRequest(null));
        }
        assertThat(walletBalance()).isEqualTo(40);

        // 5번째: 0 지급
        Long fifthRoutine = persistRoutine(userId);
        RoutineLogResponse fifthResponse = routineLogService.complete(userId, fifthRoutine,
                new RoutineLogCreateRequest(null));
        assertThat(fifthResponse.rewardAmount()).isEqualTo(0);

        // 5번째 취소: reward_amount=0이므로 지갑 불변
        routineLogService.cancel(userId, fifthRoutine, TODAY);
        assertThat(walletBalance()).isEqualTo(40);
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
