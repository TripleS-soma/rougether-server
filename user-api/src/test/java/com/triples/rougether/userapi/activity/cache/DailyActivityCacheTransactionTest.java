package com.triples.rougether.userapi.activity.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.triples.rougether.domain.activity.repository.UserDailyActivityRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.activity.service.UserDailyActivityRecorder;
import com.triples.rougether.userapi.activity.service.UserDailyActivityWriter;
import com.triples.rougether.userapi.activity.service.UserDailyActivityBatcher;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:daily-activity-cache-tx;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.hikari.maximum-pool-size=2", "spring.datasource.hikari.minimum-idle=1"})
class DailyActivityCacheTransactionTest {
    @MockitoSpyBean UserDailyActivityWriter writer;
    @Autowired UserDailyActivityBatcher batcher;
    @Autowired DailyActivitySharedCache cache;
    @Autowired UserRepository users;
    @Autowired UserDailyActivityRepository activities;
    @Autowired Clock kstClock;
    @Autowired DataSource dataSource;
    @MockitoBean(name = "dailyActivityRedis") StringRedisTemplate redis;
    private final Map<String, String> stored = new ConcurrentHashMap<>();
    private ValueOperations<String, String> values;
    private Long userId;
    private LocalDate date;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        stored.clear();
        userId = users.save(User.signUp()).getId();
        date = LocalDate.now(kstClock);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> {
            assertNoDbConnection();
            return stored.get(call.getArgument(0));
        });
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(call -> {
            assertNoDbConnection();
            // 별도 연결로 읽을 수 있는 커밋된 행이 생긴 뒤에만 공유 완료 표시를 허용함.
            assertThat(activities.countByUserIdAndActivityDate(userId, date)).isEqualTo(1);
            return stored.putIfAbsent(call.getArgument(0), call.getArgument(1)) == null;
        });
    }

    @Test
    void 독립_로컬_캐시의_다른_서버도_커밋된_공유_표시로_DB를_생략한다() {
        var oneMetrics = new SimpleMeterRegistry();
        var twoMetrics = new SimpleMeterRegistry();
        var one = new UserDailyActivityRecorder(batcher, kstClock, cache, oneMetrics);
        var two = new UserDailyActivityRecorder(batcher, kstClock, cache, twoMetrics);
        one.record(userId);
        two.record(userId);
        two.record(userId);
        assertThat(oneMetrics.get("activity.record.requests").tag("outcome", "db_committed").counter().count()).isEqualTo(1);
        assertThat(twoMetrics.get("activity.record.requests").tag("outcome", "db_committed").counter().count()).isZero();
        verify(values, times(2)).get(anyString());
        verify(values, times(1)).setIfAbsent(anyString(), anyString(), any(Duration.class));
        assertThat(activities.countByUserIdAndActivityDate(userId, date)).isEqualTo(1);
    }

    @Test
    void SQL_실행_후_커밋_직전_실패하면_완료_표시_없이_롤백하고_같은_서버에서_재시도한다() {
        var attempts = new AtomicInteger();
        var completionStatus = new AtomicInteger(-1);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            int changedRows = (int) call.callRealMethod();
            assertThat(changedRows).isEqualTo(1);
            // INSERT가 실제 실행되어 현재 트랜잭션에서는 행이 보이는지 확인함.
            assertThat(activities.countByUserIdAndActivityDate(userId, date)).isEqualTo(1);
            if (attempts.incrementAndGet() == 1) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        // 물리적 커밋 이후의 응답 유실이 아닌, 커밋 직전 실패와 롤백을 재현함.
                        throw new IllegalStateException("활동 INSERT 이후 커밋 직전 실패");
                    }

                    @Override
                    public void afterCompletion(int status) {
                        completionStatus.set(status);
                    }
                });
            }
            return changedRows;
        }).when(writer).record(userId, date);

        var metrics = new SimpleMeterRegistry();
        var recorder = new UserDailyActivityRecorder(batcher, kstClock, cache, metrics);
        recorder.record(userId);

        assertThat(attempts).hasValue(1);
        assertThat(completionStatus).hasValue(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertNoDbConnection();
        assertThat(activities.countByUserIdAndActivityDate(userId, date)).isZero();
        assertThat(stored).isEmpty();
        verify(values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        assertThat(metrics.get("activity.record.requests").tag("outcome", "failed").counter().count()).isEqualTo(1);
        assertThat(metrics.get("activity.record.requests").tag("outcome", "db_committed").counter().count()).isZero();

        // 같은 recorder의 다음 요청이 DB를 재시도해야 로컬 완료·처리 중 상태가 남지 않았음이 증명됨.
        recorder.record(userId);
        assertThat(attempts).hasValue(2);
        assertThat(activities.countByUserIdAndActivityDate(userId, date)).isEqualTo(1);
        assertThat(stored).hasSize(1).containsValue("1");
        verify(values, times(2)).get(anyString());
        verify(values).setIfAbsent(anyString(), anyString(), any(Duration.class));
        assertThat(metrics.get("activity.record.requests").tag("outcome", "db_committed").counter().count()).isEqualTo(1);
        assertThat(metrics.get("activity.record.requests").tag("outcome", "local_hit").counter().count()).isZero();

        recorder.record(userId);
        assertThat(attempts).hasValue(2);
        assertThat(metrics.get("activity.record.requests").tag("outcome", "local_hit").counter().count()).isEqualTo(1);
        verify(values, times(2)).get(anyString());
        verify(values).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void 탈퇴_봇_없는_사용자는_DB와_공유캐시에_기록하지_않는다() {
        var withdrawn = users.save(User.signUp());
        withdrawn.softDelete(kstClock.instant());
        users.save(withdrawn);
        var bot = users.save(User.bot("cache-bot-" + userId, "봇", "봇"));
        var recorder = new UserDailyActivityRecorder(batcher, kstClock, cache, new SimpleMeterRegistry());
        for (long id : new long[] {withdrawn.getId(), bot.getId(), Long.MAX_VALUE}) {
            recorder.record(id);
            recorder.record(id);
            assertThat(activities.countByUserIdAndActivityDate(id, date)).isZero();
        }
        verify(values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        assertThat(stored).isEmpty();
    }

    private void assertNoDbConnection() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections()).isZero();
    }
}
