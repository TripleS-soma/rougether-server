package com.triples.rougether.batch.weeklyreport;

import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.report.entity.WeeklyReport;
import com.triples.rougether.domain.report.repository.WeeklyReportRepository;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.data.domain.PageRequest;

// 해당 주 weekly_reports(GENERATED·FALLBACK 모두) 중 아직 WEEKLY_REPORT 알림이 없는 회고를 읽는다.
// 중복 발송 차단은 쿼리의 not exists(type + refId=회고 id)가 담당하므로 재실행돼도 다시 적재되지 않는다.
// ReminderCandidateReader 와 같은 이유로 offset 대신 id 커서 페이징(적재된 회고가 필터에서 즉시 빠짐)
@RequiredArgsConstructor
class WeeklyReportPushCandidateReader implements ItemReader<WeeklyReport> {

    private static final int PAGE_SIZE = 200;

    private final WeeklyReportRepository weeklyReportRepository;
    private final LocalDate weekStart;

    private Iterator<WeeklyReport> currentBatch = Collections.emptyIterator();
    private Long cursorId = 0L;
    private boolean exhausted = false;

    @Override
    public WeeklyReport read() {
        if (!currentBatch.hasNext() && !exhausted) {
            List<WeeklyReport> batch = fetchNextBatch();
            if (batch.isEmpty()) {
                exhausted = true;
            } else {
                currentBatch = batch.iterator();
            }
        }
        if (!currentBatch.hasNext()) {
            return null;
        }
        WeeklyReport next = currentBatch.next();
        cursorId = next.getId();
        return next;
    }

    private List<WeeklyReport> fetchNextBatch() {
        return weeklyReportRepository.findPushCandidates(weekStart, NotificationType.WEEKLY_REPORT,
                cursorId, PageRequest.of(0, PAGE_SIZE));
    }
}
