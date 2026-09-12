package com.triples.rougether.userapi.minigame;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.AuthErrorCode;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.minigame.entity.MinigameRun;
import com.triples.rougether.domain.minigame.repository.MinigameBestScoreRepository;
import com.triples.rougether.domain.minigame.repository.MinigameRunRepository;
import com.triples.rougether.userapi.member.service.MemberWithdrawalService;
import com.triples.rougether.userapi.minigame.dto.MinigameFinishRequest;
import com.triples.rougether.userapi.minigame.service.MinigameCommandService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class MinigameWithdrawalConcurrencyTest {
    private static final String GAME = "room-runner";
    private static final Instant NOW = Instant.parse("2026-09-12T10:00:00Z");
    @Autowired private UserRepository users;
    @Autowired private MinigameRunRepository runs;
    @Autowired private MinigameBestScoreRepository bestScores;
    @Autowired private MinigameCommandService commands;
    @Autowired private MemberWithdrawalService withdrawals;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private Clock kstClock;
    private User user;

    @BeforeEach
    void setUp() {
        when(kstClock.instant()).thenReturn(NOW);
        when(kstClock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        user = users.save(User.signUp());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM minigame_runs WHERE user_id = ?", user.getId());
        jdbc.update("DELETE FROM minigame_best_scores WHERE user_id = ?", user.getId());
        jdbc.update("DELETE FROM users WHERE id = ?", user.getId());
    }

    @Test
    void 기록_제출이_사용자_락을_먼저_잡아도_뒤따른_탈퇴가_최고점과_기록을_삭제한다() throws Exception {
        MinigameRun run = runs.save(MinigameRun.start(UUID.randomUUID().toString(), user, GAME, 1, 1,
                NOW.minusSeconds(60), NOW.plusSeconds(600)));
        runInOrder(() -> commands.finish(user.getId(), GAME, run.getId(), new MinigameFinishRequest(188, List.of())),
                () -> { withdrawals.withdraw(user.getId()); return null; });
        assertDeleted();
    }

    @Test
    void 탈퇴가_사용자_락을_먼저_잡으면_대기하던_새_게임이_기록을_재생성하지_못한다() throws Exception {
        Object outcome = runInOrder(() -> { withdrawals.withdraw(user.getId()); return null; }, () -> {
            try {
                return commands.start(user.getId(), GAME);
            } catch (BusinessException exception) {
                return exception.getErrorCode();
            }
        });
        assertThat(outcome).isEqualTo(AuthErrorCode.USER_NOT_FOUND);
        assertDeleted();
    }

    private void assertDeleted() {
        assertThat(users.findById(user.getId()).orElseThrow().isDeleted()).isTrue();
        assertThat(bestScores.findByUserIdAndGameCode(user.getId(), GAME)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM minigame_runs WHERE user_id = ?",
                Long.class, user.getId())).isZero();
    }

    private Object runInOrder(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch firstHoldsLock = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var firstResult = pool.submit(() -> tx.execute(status -> {
                Object result = call(first);
                firstHoldsLock.countDown();
                await(secondStarted);
                return result;
            }));
            var secondResult = pool.submit(() -> {
                await(firstHoldsLock);
                secondStarted.countDown();
                return second.call();
            });
            firstResult.get(20, TimeUnit.SECONDS);
            return secondResult.get(20, TimeUnit.SECONDS);
        }
    }

    private static Object call(Callable<?> action) {
        try {
            return action.call();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
