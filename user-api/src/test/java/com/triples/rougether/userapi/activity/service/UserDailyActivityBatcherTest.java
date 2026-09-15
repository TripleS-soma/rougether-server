package com.triples.rougether.userapi.activity.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class UserDailyActivityBatcherTest {
    private final UserDailyActivityWriter writer = mock(UserDailyActivityWriter.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final LocalDate date = LocalDate.of(2026, 9, 15);

    @Test
    void 기존_경로는_동시_최초_요청_64건에_64번의_독립_쓰기를_실행한다() throws Exception {
        when(writer.record(anyLong(), any())).thenReturn(1);
        var batcher = new UserDailyActivityBatcher(writer, metrics, false, 100, 512, 2);
        assertThat(concurrent(batcher, 64)).containsOnly(1);
        verify(writer, times(64)).record(anyLong(), any());
        verify(writer, never()).recordBatch(anyList());
    }

    @Test
    void 동시_최초_요청은_묶어서_쓰고_각_사용자의_결과를_돌려준다() throws Exception {
        when(writer.recordBatch(anyList())).thenAnswer(call -> new HashSet<>(call.getArgument(0, List.class)));
        var batcher = new UserDailyActivityBatcher(writer, metrics, true, 100, 512, 10);
        assertThat(concurrent(batcher, 64)).hasSize(64).containsOnly(1);
        assertThat(metrics.get("activity.batch.transactions").tag("outcome", "committed").counter().count())
                .isBetween(1.0, 63.0);
        assertThat(metrics.get("activity.batch.users").summary().totalAmount()).isEqualTo(64);
        verify(writer, never()).record(anyLong(), any());
    }

    @Test
    void 같은_묶음의_기록_성공과_제외_결과를_다른_사용자에게_섞지_않는다() throws Exception {
        when(writer.recordBatch(anyList())).thenReturn(java.util.Set.of(new UserDailyActivityBatcher.Activity(1L, date)));
        var batcher = new UserDailyActivityBatcher(writer, metrics, true, 100, 512, 10);
        assertThat(concurrent(batcher, 2)).containsExactly(1, 0);
    }

    @Test
    void 커밋_결과가_나올_때까지_대기하며_실패_후_다음_기록도_진행한다() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(writer.recordBatch(anyList())).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            throw new IllegalStateException("commit failed");
        }).thenAnswer(call -> new HashSet<>(call.getArgument(0, List.class)));
        var batcher = new UserDailyActivityBatcher(writer, metrics, true, 2, 2, 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> result = executor.submit(() -> batcher.record(1L, date));
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(result.isDone()).isFalse();
            } finally {
                release.countDown();
            }
            assertThatThrownBy(() -> result.get(3, TimeUnit.SECONDS)).hasRootCauseMessage("commit failed");
            assertThat(batcher.record(1L, date)).isEqualTo(1);
        }
    }

    @Test
    void DB_대기_중에도_대기열_상한을_지키고_접수한_모든_요청을_완료한다() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(writer.recordBatch(anyList())).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(call.getArgument(0, List.class).size()).isLessThanOrEqualTo(2);
            return new HashSet<>(call.getArgument(0, List.class));
        });
        var batcher = new UserDailyActivityBatcher(writer, metrics, true, 2, 2, 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> batcher.record(1L, date));
            List<Future<Integer>> pending = new ArrayList<>();
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                for (long id = 2; id <= 3; id++) {
                    long user = id;
                    pending.add(executor.submit(() -> batcher.record(user, date)));
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (metrics.get("activity.batch.queued").gauge().value() != 2 && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }
                assertThat(metrics.get("activity.batch.queued").gauge().value()).isEqualTo(2);
                assertThatThrownBy(() -> batcher.record(4L, date)).isInstanceOf(RejectedExecutionException.class);
            } finally {
                release.countDown();
            }
            assertThat(first.get(3, TimeUnit.SECONDS)).isEqualTo(1);
            for (var result : pending) assertThat(result.get(3, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(batcher.record(4L, date)).isEqualTo(1);
            assertThat(metrics.get("activity.batch.queued").gauge().value()).isZero();
        }
    }

    private List<Integer> concurrent(UserDailyActivityBatcher batcher, int count) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CountDownLatch(count);
            var start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (long id = 1; id <= count; id++) {
                long user = id;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return batcher.record(user, date);
                }));
            }
            try { assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue(); }
            finally { start.countDown(); }
            List<Integer> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(5, TimeUnit.SECONDS));
            return results;
        }
    }
}
