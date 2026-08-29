package com.triples.rougether.userapi.activity.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserDailyActivityRecorder {

    private final UserDailyActivityWriter writer;
    private final Clock kstClock;

    // 정상 요청의 매번 DB 접근을 피함. 자정 경합에서 직전 날짜 요청이 늦게 끝날 수 있어 최근 이틀은 유지함.
    private final ConcurrentMap<LocalDate, Set<Long>> recordedUserIdsByDate = new ConcurrentHashMap<>();
    private final AtomicReference<LocalDate> lastPrunedDate = new AtomicReference<>();

    public void record(Long userId) {
        if (userId == null) {
            return;
        }

        LocalDate activityDate = LocalDate.now(kstClock);
        pruneOldDateCaches(activityDate);
        Set<Long> recordedUserIds = recordedUserIdsByDate.computeIfAbsent(
                activityDate, ignored -> ConcurrentHashMap.newKeySet());
        if (!recordedUserIds.add(userId)) {
            return;
        }

        try {
            if (writer.record(userId, activityDate) == 0) {
                // 대상 아님(탈퇴·봇)으로 기록되지 않은 것 - "기록됨"으로 캐시에 고정하면 원천 누락이 무증상이 됨.
                // 캐시를 되돌려 상태가 바뀌면(집계상 있어야 할 사용자) 다음 요청이 다시 시도하게 함.
                recordedUserIds.remove(userId);
                log.debug("일별 사용자 활동 기록 대상이 아닙니다. userId={}, activityDate={}", userId, activityDate);
            }
        } catch (RuntimeException e) {
            // 관측 실패가 사용자 요청을 실패시키면 안 됨. 캐시는 되돌려 다음 요청이 재시도하게 함.
            recordedUserIds.remove(userId);
            log.warn("일별 사용자 활동 기록에 실패했습니다. userId={}, activityDate={}",
                    userId, activityDate, e);
        }
    }

    private void pruneOldDateCaches(LocalDate activityDate) {
        LocalDate previous = lastPrunedDate.getAndSet(activityDate);
        if (activityDate.equals(previous)) {
            return;
        }
        LocalDate oldestKeptDate = activityDate.minusDays(1);
        recordedUserIdsByDate.keySet().removeIf(date -> date.isBefore(oldestKeptDate));
    }
}
