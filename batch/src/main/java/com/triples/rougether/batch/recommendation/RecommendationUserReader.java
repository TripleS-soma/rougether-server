package com.triples.rougether.batch.recommendation;

import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.data.domain.PageRequest;

// 조정 추천 대상 사용자 id 를 keyset 페이지로 읽는다 — 근거 창(3주)에 COMPLETED/FAILED log 가 있는 사용자
// (탈퇴·봇 제외는 쿼리 공통 술어). 살아있는 루틴의 log 기준 술어라, 창 내 log 가 전부 닫힌 옛 버전에만 있는
// 사용자는 빠질 수 있다 — 방금 스케줄을 바꾼 사용자라 이번 주는 건너뛰는 보수적 동작으로 수용함(#329).
// 이번 job 이 routine_recommendations 에 insert 해도 routine_logs 기준 커서라 페이지가 흔들리지 않는다.
@RequiredArgsConstructor
class RecommendationUserReader implements ItemReader<Long> {

    private static final int PAGE_SIZE = 100;

    private final RoutineLogRepository routineLogRepository;
    private final LocalDate windowStart;
    private final LocalDate windowEnd;

    private Iterator<Long> currentBatch = Collections.emptyIterator();
    private long cursorUserId = 0L;
    private boolean exhausted = false;

    @Override
    public Long read() {
        if (!currentBatch.hasNext() && !exhausted) {
            List<Long> batch = routineLogRepository.findUserIdsWithLogsInPeriod(
                    windowStart, windowEnd, RecommendationPolicy.COUNTED_LOG_STATUSES,
                    cursorUserId, PageRequest.of(0, PAGE_SIZE));
            if (batch.isEmpty()) {
                exhausted = true;
            } else {
                currentBatch = batch.iterator();
            }
        }
        if (!currentBatch.hasNext()) {
            return null;
        }
        Long next = currentBatch.next();
        cursorUserId = next;
        return next;
    }
}
