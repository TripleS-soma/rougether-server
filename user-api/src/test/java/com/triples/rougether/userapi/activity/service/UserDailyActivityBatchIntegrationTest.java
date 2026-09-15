package com.triples.rougether.userapi.activity.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.triples.rougether.domain.activity.repository.UserDailyActivityRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.activity.cache.DailyActivitySharedCache;
import com.triples.rougether.userapi.activity.service.UserDailyActivityBatcher.Activity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:mysql:8.4:///activity_batch_transactions",
        "spring.datasource.username=test", "spring.datasource.password=test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.hikari.maximum-pool-size=8",
        "activity.batch.collect-millis=10"})
@ActiveProfiles("mysql")
class UserDailyActivityBatchIntegrationTest {
    @Autowired UserRepository users;
    @Autowired UserDailyActivityRepository activities;
    @Autowired UserDailyActivityBatcher batcher;
    @Autowired Clock kstClock;
    @Autowired TransactionTemplate transactions;
    @MockitoSpyBean UserDailyActivityWriter writer;
    @MockitoBean DailyActivitySharedCache cache;

    @Test
    void 묶음은_중복과_날짜를_구분하고_봇과_탈퇴와_없는_사용자를_제외한다() {
        Long one = users.save(User.signUp()).getId();
        Long two = users.save(User.signUp()).getId();
        var deleted = User.signUp();
        deleted.softDelete(kstClock.instant());
        Long withdrawn = users.save(deleted).getId();
        Long bot = users.save(User.bot("batch-bot", "봇", "봇")).getId();
        LocalDate date = LocalDate.of(2026, 9, 14);
        var expected = List.of(new Activity(one, date), new Activity(two, date), new Activity(one, date.plusDays(1)));
        var input = new ArrayList<>(expected);
        input.addAll(List.of(expected.getFirst(), new Activity(withdrawn, date), new Activity(bot, date),
                new Activity(Long.MAX_VALUE, date)));

        transactions.executeWithoutResult(tx -> {
            assertThat(writer.recordBatch(input)).containsExactlyInAnyOrderElementsOf(expected);
            tx.setRollbackOnly();
        });
        for (var row : expected) {
            assertThat(activities.countByUserIdAndActivityDate(row.userId(), row.date())).isEqualTo(1);
        }
        assertThat(writer.recordBatch(input)).containsExactlyInAnyOrderElementsOf(expected);
        for (var row : expected) assertThat(activities.countByUserIdAndActivityDate(row.userId(), row.date())).isEqualTo(1);
        for (Long id : List.of(withdrawn, bot, Long.MAX_VALUE)) {
            assertThat(activities.countByUserIdAndActivityDate(id, date)).isZero();
        }
    }

    @Test
    void 동시_기록은_커밋된_행에만_캐시를_표시하고_실패한_묶음은_모두_재시도한다() throws Exception {
        var ids = new ArrayList<Long>();
        for (int i = 0; i < 24; i++) ids.add(users.save(User.signUp()).getId());
        LocalDate date = LocalDate.now(kstClock);
        var failing = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (failing.get()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) {
                        throw new IllegalStateException("batch commit failed");
                    }
                });
            }
            return result;
        }).when(writer).recordBatch(anyList());
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(activities.countByUserIdAndActivityDate(call.getArgument(0), call.getArgument(1))).isEqualTo(1);
            return null;
        }).when(cache).markRecorded(anyLong(), any());
        var metrics = new SimpleMeterRegistry();
        var recorder = new UserDailyActivityRecorder(batcher, kstClock, cache, metrics);

        recordTogether(recorder, ids);
        for (Long id : ids) assertThat(activities.countByUserIdAndActivityDate(id, date)).isZero();
        verify(cache, never()).markRecorded(anyLong(), any());
        assertThat(metrics.get("activity.record.requests").tag("outcome", "failed").counter().count()).isEqualTo(24);

        failing.set(false);
        recordTogether(recorder, ids);
        recordTogether(recorder, ids);
        for (Long id : ids) {
            assertThat(activities.countByUserIdAndActivityDate(id, date)).isEqualTo(1);
            verify(cache, times(1)).markRecorded(id, date);
        }
        assertThat(metrics.get("activity.record.requests").tag("outcome", "db_committed").counter().count()).isEqualTo(24);
        assertThat(metrics.get("activity.record.requests").tag("outcome", "local_hit").counter().count()).isEqualTo(24);
    }

    private void recordTogether(UserDailyActivityRecorder recorder, List<Long> ids) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (Long id : ids) futures.add(executor.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                recorder.record(id);
                return null;
            }));
            start.countDown();
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
        }
    }
}
