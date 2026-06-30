package com.triples.rougether.userapi.routine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.Streak;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.routine.dto.RoutineLogCreateRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest
class RoutineCompletionRollbackTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired
    private RoutineLogService service;
    @Autowired
    private RoutineRepository routineRepository;
    @Autowired
    private RoutineLogRepository routineLogRepository;
    @Autowired
    private UserWalletRepository userWalletRepository;
    @Autowired
    private UserRepository userRepository;

    @MockitoSpyBean
    private StreakRepository streakRepository;

    private Long userId;
    private Long routineId;
    private Long walletId;
    private Long streakId;
    private Long logId;

    @AfterEach
    void cleanUp() {
        // 테스트 트랜잭션이 없어 직접 정리함.
        if (logId != null) {
            routineLogRepository.deleteById(logId);
        }
        if (streakId != null) {
            streakRepository.deleteById(streakId);
        }
        if (walletId != null) {
            userWalletRepository.deleteById(walletId);
        }
        if (routineId != null) {
            routineRepository.deleteById(routineId);
        }
        if (userId != null) {
            userRepository.deleteById(userId);
        }
    }

    @Test
    void 완료_중_스트릭_단계가_실패하면_log와_코인이_전부_롤백된다() {
        User user = userRepository.save(User.signUp());
        userId = user.getId();
        routineId = persistRoutine(user);
        walletId = persistWallet(user, 0);

        // 오늘 첫 완료라 Streak.start → save 경로를 타고, 그 단계에서 터뜨림
        doThrow(new RuntimeException("스트릭 저장 실패")).when(streakRepository).save(any());

        assertThatThrownBy(() -> service.complete(userId, routineId, new RoutineLogCreateRequest(null)))
                .isInstanceOf(RuntimeException.class);

        assertThat(routineLogRepository.findByRoutineIdAndRoutineDate(routineId, TODAY)).isEmpty();
        assertThat(walletBalance()).isEqualTo(0);
    }

    @Test
    void 취소_중_스트릭_단계가_실패하면_log와_코인이_전부_롤백된다() {
        User user = userRepository.save(User.signUp());
        userId = user.getId();
        routineId = persistRoutine(user);
        walletId = persistWallet(user, 10);
        streakId = persistStreak(user);
        logId = persistTodayLog(routineId);

        doThrow(new RuntimeException("스트릭 롤백 실패")).when(streakRepository).findByUserId(anyLong());

        assertThatThrownBy(() -> service.cancel(userId, routineId, TODAY))
                .isInstanceOf(RuntimeException.class);

        assertThat(routineLogRepository.findById(logId)).isPresent();
        assertThat(walletBalance()).isEqualTo(10);
    }

    private Long persistRoutine(User owner) {
        Routine routine = Routine.create(owner, null, "아침 운동", AuthType.CHECK,
                null, null, null, null, null);
        return routineRepository.save(routine).getId();
    }

    private Long persistWallet(User owner, int balance) {
        UserWallet wallet = BeanUtils.instantiateClass(UserWallet.class);
        ReflectionTestUtils.setField(wallet, "user", owner);
        ReflectionTestUtils.setField(wallet, "currencyType", CurrencyType.COIN);
        ReflectionTestUtils.setField(wallet, "balance", balance);
        return userWalletRepository.save(wallet).getId();
    }

    private Long persistStreak(User owner) {
        return streakRepository.save(Streak.start(owner, TODAY)).getId();
    }

    private Long persistTodayLog(Long routineId) {
        Routine routine = routineRepository.findById(routineId).orElseThrow();
        RoutineLog log = RoutineLog.complete(routine, TODAY, Instant.now(), CurrencyType.COIN, 10);
        return routineLogRepository.save(log).getId();
    }

    private int walletBalance() {
        return userWalletRepository.findById(walletId).orElseThrow().getBalance();
    }
}
