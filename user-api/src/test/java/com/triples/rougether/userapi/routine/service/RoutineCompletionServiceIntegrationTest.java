package com.triples.rougether.userapi.routine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.Streak;
import com.triples.rougether.domain.routine.entity.StreakStatus;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.userapi.routine.reward.service.DailyRewardService;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.routine.dto.RoutineLogCreateRequest;
import com.triples.rougether.userapi.routine.dto.RoutineLogResponse;
import com.triples.rougether.userapi.routine.error.RoutineErrorCode;
import com.triples.rougether.userapi.routine.error.RoutineLogErrorCode;
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
class RoutineCompletionServiceIntegrationTest {

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

    private RoutineLogService service;
    private Long userId;
    private Long routineId;

    @BeforeEach
    void setUp() {
        DailyRewardService dailyRewardService = new DailyRewardService(routineLogRepository,
                todoRepository);
        service = new RoutineLogService(routineRepository, routineLogRepository,
                userWalletRepository, streakRepository, dailyRewardService,
                new TransactionTemplate(transactionManager));
        User user = userRepository.save(User.signUp());
        userId = user.getId();
        routineId = persistRoutine(user);
        persistWallet(user, 0);
    }

    @Test
    void 완료하면_COMPLETED_log와_코인10이_지급된다() {
        RoutineLogResponse response = service.complete(userId, routineId, new RoutineLogCreateRequest(null));

        assertThat(response.status()).isEqualTo(RoutineLogStatus.COMPLETED);
        assertThat(response.rewardCurrencyType()).isEqualTo(CurrencyType.COIN);
        assertThat(response.rewardAmount()).isEqualTo(10);
        assertThat(response.routineDate()).isEqualTo(TODAY);
        assertThat(walletBalance()).isEqualTo(10);
    }

    @Test
    void 같은날_재완료하면_ALREADY_COMPLETED() {
        service.complete(userId, routineId, new RoutineLogCreateRequest(null));

        assertThatThrownBy(() -> service.complete(userId, routineId, new RoutineLogCreateRequest(null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineLogErrorCode.ALREADY_COMPLETED);
    }

    @Test
    void 타인_루틴_완료는_ROUTINE_NOT_FOUND() {
        assertThatThrownBy(() -> service.complete(userId, otherUsersRoutine(),
                new RoutineLogCreateRequest(null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.ROUTINE_NOT_FOUND);
    }

    @Test
    void 미래_날짜_완료는_INVALID_ROUTINE_DATE() {
        assertThatThrownBy(() -> service.complete(userId, routineId,
                new RoutineLogCreateRequest(TODAY.plusDays(1))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineLogErrorCode.INVALID_ROUTINE_DATE);
    }

    @Test
    void 과거_날짜_완료는_코인0_지갑불변_스트릭불변() {
        persistStreak(userId, 3, TODAY.minusDays(1));

        RoutineLogResponse response = service.complete(userId, routineId,
                new RoutineLogCreateRequest(TODAY.minusDays(2)));

        assertThat(response.status()).isEqualTo(RoutineLogStatus.COMPLETED);
        assertThat(response.routineDate()).isEqualTo(TODAY.minusDays(2));
        assertThat(response.rewardAmount()).isZero();
        assertThat(walletBalance()).isZero();
        // 스트릭은 소급하지 않고 기존 값 그대로 반환
        assertThat(response.streak().currentCount()).isEqualTo(3);
        assertThat(response.streak().lastSuccessDate()).isEqualTo(TODAY.minusDays(1));
    }

    @Test
    void 스트릭이_없을때_과거_완료는_빈_스트릭을_반환한다() {
        RoutineLogResponse response = service.complete(userId, routineId,
                new RoutineLogCreateRequest(TODAY.minusDays(1)));

        assertThat(response.streak().currentCount()).isZero();
        assertThat(response.streak().lastSuccessDate()).isNull();
        assertThat(streakRepository.findByUserId(userId)).isEmpty();
    }

    @Test
    void 같은_과거_날짜_재완료는_ALREADY_COMPLETED() {
        service.complete(userId, routineId, new RoutineLogCreateRequest(TODAY.minusDays(1)));

        assertThatThrownBy(() -> service.complete(userId, routineId,
                new RoutineLogCreateRequest(TODAY.minusDays(1))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineLogErrorCode.ALREADY_COMPLETED);
    }

    @Test
    void 지갑이_없으면_WALLET_NOT_FOUND() {
        User noWallet = userRepository.save(User.signUp());
        Long noWalletRoutine = persistRoutine(noWallet);

        assertThatThrownBy(() -> service.complete(noWallet.getId(), noWalletRoutine,
                new RoutineLogCreateRequest(null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineLogErrorCode.WALLET_NOT_FOUND);
    }

    @Test
    void 어제_성공이면_스트릭이_1_증가한다() {
        persistStreak(userId, 3, TODAY.minusDays(1));

        RoutineLogResponse response = service.complete(userId, routineId, new RoutineLogCreateRequest(null));

        assertThat(response.streak().currentCount()).isEqualTo(4);
        assertThat(response.streak().lastSuccessDate()).isEqualTo(TODAY);
    }

    @Test
    void 비연속이면_스트릭이_1로_리셋된다() {
        persistStreak(userId, 5, TODAY.minusDays(3));

        RoutineLogResponse response = service.complete(userId, routineId, new RoutineLogCreateRequest(null));

        assertThat(response.streak().currentCount()).isEqualTo(1);
        assertThat(response.streak().longestCount()).isEqualTo(5); // longest는 줄지 않음
    }

    @Test
    void 스트릭이_없으면_생성되고_1로_시작한다() {
        RoutineLogResponse response = service.complete(userId, routineId, new RoutineLogCreateRequest(null));

        assertThat(response.streak().currentCount()).isEqualTo(1);
        assertThat(response.streak().longestCount()).isEqualTo(1);
        assertThat(streakRepository.findByUserId(userId)).isPresent();
    }

    @Test
    void 같은날_두번째_완료는_스트릭을_바꾸지_않는다() {
        Long secondRoutine = persistRoutine(userRepository.findById(userId).orElseThrow());
        service.complete(userId, routineId, new RoutineLogCreateRequest(null));

        RoutineLogResponse second = service.complete(userId, secondRoutine, new RoutineLogCreateRequest(null));

        assertThat(second.streak().currentCount()).isEqualTo(1);
        assertThat(second.streak().lastSuccessDate()).isEqualTo(TODAY);
    }

    private Long otherUsersRoutine() {
        User other = userRepository.save(User.signUp());
        return persistRoutine(other);
    }

    private Long persistRoutine(User owner) {
        Routine routine = Routine.create(owner, null, "아침 운동", AuthType.CHECK,
                null, null, null, null, null);
        return routineRepository.save(routine).getId();
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
