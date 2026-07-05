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
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.global.config.JpaConfig;
import com.triples.rougether.userapi.routine.dto.RoutineLogCreateRequest;
import com.triples.rougether.userapi.routine.dto.RoutineLogResponse;
import com.triples.rougether.userapi.routine.dto.StreakSummaryResponse;
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

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class RoutineCancelServiceIntegrationTest {

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

    private RoutineLogService service;
    private Long userId;
    private Long routineId;

    @BeforeEach
    void setUp() {
        // 이벤트 구독(미션 기여)은 이 슬라이스 범위 밖 — no-op publisher 로 대체
        service = new RoutineLogService(routineRepository, routineLogRepository,
                userWalletRepository, streakRepository, event -> { });
        User user = userRepository.save(User.signUp());
        userId = user.getId();
        routineId = persistRoutine(user);
        persistWallet(user, 0);
    }

    @Test
    void 취소하면_log가_삭제되고_코인이_회수되며_스트릭이_롤백된다() {
        persistStreak(userId, 3, TODAY.minusDays(1));
        RoutineLogResponse completed = service.complete(userId, routineId, new RoutineLogCreateRequest(null));
        assertThat(walletBalance()).isEqualTo(10);

        StreakSummaryResponse streak = service.cancel(userId, routineId, TODAY);

        assertThat(routineLogRepository.findById(completed.id())).isEmpty();
        assertThat(walletBalance()).isEqualTo(0);
        assertThat(streak.currentCount()).isEqualTo(3);
        assertThat(streak.lastSuccessDate()).isEqualTo(TODAY.minusDays(1));
    }

    @Test
    void 오늘_다른_완료가_남으면_스트릭은_변하지_않는다() {
        Long secondRoutine = persistRoutine(userRepository.findById(userId).orElseThrow());
        service.complete(userId, routineId, new RoutineLogCreateRequest(null));
        service.complete(userId, secondRoutine, new RoutineLogCreateRequest(null));

        service.cancel(userId, secondRoutine, TODAY);

        Streak streak = streakRepository.findByUserId(userId).orElseThrow();
        assertThat(streak.getCurrentCount()).isEqualTo(1);
        assertThat(streak.getLastSuccessDate()).isEqualTo(TODAY);
    }

    @Test
    void 오늘이_아닌_날짜_취소는_LOG_NOT_CANCELABLE() {
        service.complete(userId, routineId, new RoutineLogCreateRequest(null));

        // 과거 날짜로 취소 시도 → 오늘 걸 취소하지 않고 거부함
        assertThatThrownBy(() -> service.cancel(userId, routineId, TODAY.minusDays(2)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineLogErrorCode.LOG_NOT_CANCELABLE);
        // 오늘 완료는 그대로 남아 있어야 함
        assertThat(routineLogRepository
                .findByRoutineIdAndRoutineDateAndStatus(routineId, TODAY, RoutineLogStatus.COMPLETED))
                .isPresent();
    }

    @Test
    void 타인_루틴_취소는_ROUTINE_NOT_FOUND() {
        User other = userRepository.save(User.signUp());
        Long otherRoutine = persistRoutine(other);
        persistWallet(other, 0);
        service.complete(other.getId(), otherRoutine, new RoutineLogCreateRequest(null));

        assertThatThrownBy(() -> service.cancel(userId, otherRoutine, TODAY))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.ROUTINE_NOT_FOUND);
    }

    @Test
    void 받은_코인을_소비한_뒤_취소하면_잔액이_음수가_되어도_취소된다() {
        service.complete(userId, routineId, new RoutineLogCreateRequest(null)); // +10
        spendAllCoins(); // 다른 곳에서 소비해 잔액 0

        service.cancel(userId, routineId, TODAY);

        // 음수 잔액을 허용하므로 취소가 성공하고 완료 기록이 삭제됨
        assertThat(routineLogRepository
                .findByRoutineIdAndRoutineDateAndStatus(routineId, TODAY, RoutineLogStatus.COMPLETED))
                .isEmpty();
        // 잔액 0에서 10을 회수해 -10이 됨
        assertThat(walletBalance()).isEqualTo(-10);
    }

    @Test
    void 오늘_완료가_없는_루틴_취소는_ROUTINE_LOG_NOT_FOUND() {
        Long notCompleted = persistRoutine(userRepository.findById(userId).orElseThrow());

        assertThatThrownBy(() -> service.cancel(userId, notCompleted, TODAY))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineLogErrorCode.ROUTINE_LOG_NOT_FOUND);
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

    private void spendAllCoins() {
        UserWallet wallet = userWalletRepository.findByUserIdAndCurrencyType(userId, CurrencyType.COIN)
                .orElseThrow();
        ReflectionTestUtils.setField(wallet, "balance", 0);
        userWalletRepository.save(wallet);
    }

    private int walletBalance() {
        return userWalletRepository.findByUserIdAndCurrencyType(userId, CurrencyType.COIN)
                .orElseThrow().getBalance();
    }
}
