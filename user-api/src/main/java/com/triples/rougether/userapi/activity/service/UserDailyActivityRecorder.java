package com.triples.rougether.userapi.activity.service;

import com.triples.rougether.userapi.activity.cache.DailyActivitySharedCache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class UserDailyActivityRecorder {

    private final UserDailyActivityBatcher writer;
    private final Clock kstClock;
    private final DailyActivitySharedCache sharedCache;
    private final Counter localHit;
    private final Counter committed;
    private final Counter excluded;
    private final Counter failed;

    // 정상 요청의 매번 DB 접근을 피함. 자정 경합에서 직전 날짜 요청이 늦게 끝날 수 있어 최근 이틀은 유지함.
    private final ConcurrentMap<LocalDate, Set<Long>> recordedUserIdsByDate = new ConcurrentHashMap<>();
    private final ConcurrentMap<LocalDate, Set<Long>> inFlightUserIdsByDate = new ConcurrentHashMap<>();
    private final AtomicReference<LocalDate> lastPrunedDate = new AtomicReference<>();

    public UserDailyActivityRecorder(UserDailyActivityBatcher writer, Clock kstClock,
            DailyActivitySharedCache sharedCache, MeterRegistry registry) {
        this.writer = writer;
        this.kstClock = kstClock;
        this.sharedCache = sharedCache;
        localHit = registry.counter("activity.record.requests", "outcome", "local_hit");
        committed = registry.counter("activity.record.requests", "outcome", "db_committed");
        excluded = registry.counter("activity.record.requests", "outcome", "excluded");
        failed = registry.counter("activity.record.requests", "outcome", "failed");
    }

    public void record(Long userId) {
        if (userId == null) {
            return;
        }

        LocalDate activityDate = LocalDate.now(kstClock);
        pruneOldDateCaches(activityDate);
        Set<Long> recordedUserIds = recordedUserIdsByDate.computeIfAbsent(
                activityDate, ignored -> ConcurrentHashMap.newKeySet());
        if (recordedUserIds.contains(userId)) {
            localHit.increment();
            return;
        }
        Set<Long> inFlight = inFlightUserIdsByDate.computeIfAbsent(
                activityDate, ignored -> ConcurrentHashMap.newKeySet());
        if (!inFlight.add(userId)) return;

        try {
            // 최초 검사와 처리 중 등록 사이에 다른 요청이 완료했을 수 있음.
            if (recordedUserIds.contains(userId)) return;
            if (sharedCache.isRecorded(userId, activityDate)) {
                recordedUserIds.add(userId);
                return;
            }
            if (writer.record(userId, activityDate) == 0) {
                // 대상 아님(탈퇴·봇)으로 기록되지 않은 것 - "기록됨"으로 캐시에 고정하면 원천 누락이 무증상이 됨.
                // 캐시를 되돌려 상태가 바뀌면(집계상 있어야 할 사용자) 다음 요청이 다시 시도하게 함.
                excluded.increment();
                log.debug("일별 사용자 활동 기록 대상이 아닙니다. userId={}, activityDate={}", userId, activityDate);
                return;
            }
            committed.increment();
            recordedUserIds.add(userId);
            sharedCache.markRecorded(userId, activityDate);
        } catch (RuntimeException e) {
            // 관측 실패가 사용자 요청을 실패시키면 안 됨. 캐시는 되돌려 다음 요청이 재시도하게 함.
            recordedUserIds.remove(userId);
            failed.increment();
            log.warn("일별 사용자 활동 기록에 실패했습니다. userId={}, activityDate={}",
                    userId, activityDate, e);
        } finally {
            inFlight.remove(userId);
        }
    }

    private void pruneOldDateCaches(LocalDate activityDate) {
        LocalDate previous = lastPrunedDate.getAndSet(activityDate);
        if (activityDate.equals(previous)) {
            return;
        }
        LocalDate oldestKeptDate = activityDate.minusDays(1);
        recordedUserIdsByDate.keySet().removeIf(date -> date.isBefore(oldestKeptDate));
        inFlightUserIdsByDate.keySet().removeIf(date -> date.isBefore(oldestKeptDate));
    }
}
