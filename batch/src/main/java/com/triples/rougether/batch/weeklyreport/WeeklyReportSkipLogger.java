package com.triples.rougether.batch.weeklyreport;

import com.triples.rougether.domain.report.entity.WeeklyReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.listener.SkipListener;

// fault-tolerant skip 은 조용히 넘어가므로, 어떤 사용자가 어느 단계에서 왜 빠졌는지 warn 으로 남긴다.
// (그 주 회고는 JobInstance·unique 때문에 자동 재시도 경로가 없어 운영자가 로그로만 알 수 있다)
@Slf4j
class WeeklyReportSkipLogger implements SkipListener<Long, WeeklyReport> {

    @Override
    public void onSkipInRead(Throwable t) {
        log.warn("주간 회고 reader skip", t);
    }

    @Override
    public void onSkipInProcess(Long userId, Throwable t) {
        log.warn("주간 회고 process skip - userId={}", userId, t);
    }

    @Override
    public void onSkipInWrite(WeeklyReport report, Throwable t) {
        log.warn("주간 회고 write skip - userId={}, weekStart={}",
                report.getUser().getId(), report.getWeekStartDate(), t);
    }
}
