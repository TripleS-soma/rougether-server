package com.triples.rougether.batch.weeklyreport;

import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.data.domain.PageRequest;

// 회고 대상 사용자 id를 keyset 페이지로 읽는다(그 주에 COMPLETED/FAILED 로그가 있는 사용자, 탈퇴·삭제 루틴 제외).
// 이번 job이 weekly_reports 에 insert 해도 routine_logs 기준 커서라 페이지가 흔들리지 않는다.
@RequiredArgsConstructor
class WeeklyReportUserReader implements ItemReader<Long> {

    static final List<RoutineLogStatus> COUNTED_STATUSES =
            List.of(RoutineLogStatus.COMPLETED, RoutineLogStatus.FAILED);
    private static final int PAGE_SIZE = 100;

    private final RoutineLogRepository routineLogRepository;
    private final LocalDate weekStart;
    private final LocalDate weekEnd;

    private Iterator<Long> currentBatch = Collections.emptyIterator();
    private long cursorUserId = 0L;
    private boolean exhausted = false;

    @Override
    public Long read() {
        if (!currentBatch.hasNext() && !exhausted) {
            List<Long> batch = routineLogRepository.findUserIdsWithLogsInPeriod(
                    weekStart, weekEnd, COUNTED_STATUSES, cursorUserId, PageRequest.of(0, PAGE_SIZE));
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
