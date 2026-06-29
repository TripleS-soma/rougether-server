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
import com.triples.rougether.domain.routine.entity.RoutineLog;
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
import com.triples.rougether.userapi.routine.error.RoutineLogErrorCode;
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
        service = new RoutineLogService(routineRepository, routineLogRepository,
                userWalletRepository, streakRepository);
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

        StreakSummaryResponse streak = service.cancel(userId, routineId, completed.id());

        assertThat(routineLogRepository.findById(completed.id())).isEmpty();
        assertThat(walletBalance()).isEqualTo(0);
        assertThat(streak.currentCount()).isEqualTo(3);
        assertThat(streak.lastSuccessDate()).isEqualTo(TODAY.minusDays(1));
    }

    @Test
    void 오늘_다른_완료가_남으면_스트릭은_변하지_않는다() {
        Long secondRoutine = persistRoutine(userRepository.findById(userId).orElseThrow());
        service.complete(userId, routineId, new RoutineLogCreateRequest(null));
        RoutineLogResponse second = service.complete(userId, secondRoutine, new RoutineLogCreateRequest(null));

        service.cancel(userId, secondRoutine, second.id());

        Streak streak = streakRepository.findByUserId(userId).orElseThrow();
        assertThat(streak.getCurrentCount()).isEqualTo(1);
        assertThat(streak.getLastSuccessDate()).isEqualTo(TODAY);
    }

    @Test
    void 당일이_아닌_log_취소는_LOG_NOT_CANCELABLE() {
        Long pastLogId = persistPastLog(routineId, TODAY.minusDays(2));

        assertThatThrownBy(() -> service.cancel(userId, routineId, pastLogId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineLogErrorCode.LOG_NOT_CANCELABLE);
    }

    @Test
    void 타인_log_취소는_ROUTINE_LOG_NOT_FOUND() {
        User other = userRepository.save(User.signUp());
        Long otherRoutine = persistRoutine(other);
        persistWallet(other, 0);
        RoutineLogResponse othersLog = service.complete(other.getId(), otherRoutine,
                new RoutineLogCreateRequest(null));

        assertThatThrownBy(() -> service.cancel(userId, otherRoutine, othersLog.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineLogErrorCode.ROUTINE_LOG_NOT_FOUND);
    }

    @Test
    void path의_routineId가_log의_루틴과_다르면_ROUTINE_LOG_NOT_FOUND() {
        Long otherRoutine = persistRoutine(userRepository.findById(userId).orElseThrow());
        RoutineLogResponse log = service.complete(userId, routineId, new RoutineLogCreateRequest(null));

        // 같은 유저가 소유하지만 log는 routineId 소속 → otherRoutine 경로로는 취소 불가
        assertThatThrownBy(() -> service.cancel(userId, otherRoutine, log.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineLogErrorCode.ROUTINE_LOG_NOT_FOUND);
    }

    private Long persistRoutine(User owner) {
        Routine routine = Routine.create(owner, null, "아침 운동", AuthType.CHECK,
                null, null, null, null, null);
        return routineRepository.save(routine).getId();
    }

    private Long persistPastLog(Long routineId, LocalDate date) {
        Routine routine = routineRepository.findById(routineId).orElseThrow();
        RoutineLog log = RoutineLog.complete(routine, date, Instant.now(), CurrencyType.COIN, 10);
        return routineLogRepository.save(log).getId();
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
