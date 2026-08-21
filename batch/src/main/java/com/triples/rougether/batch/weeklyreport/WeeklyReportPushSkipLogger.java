package com.triples.rougether.batch.weeklyreport;

import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.report.entity.WeeklyReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.listener.SkipListener;

// 주간 회고 push 적재 skip 로그. job 이 그 주 한 번만 COMPLETED 되므로 적재가 skip 된 사용자는
// 그 주 push 를 영영 못 받는다 - 어떤 회고가 왜 빠졌는지 warn 으로 반드시 남긴다
@Slf4j
class WeeklyReportPushSkipLogger implements SkipListener<WeeklyReport, Notification> {

    @Override
    public void onSkipInRead(Throwable t) {
        log.warn("주간 회고 push reader skip", t);
    }

    @Override
    public void onSkipInProcess(WeeklyReport report, Throwable t) {
        log.warn("주간 회고 push process skip - reportId={}, userId={}", report.getId(), report.getUser().getId(), t);
    }

    @Override
    public void onSkipInWrite(Notification notification, Throwable t) {
        log.warn("주간 회고 push write skip - userId={}, refId={}",
                notification.getUser().getId(), notification.getRefId(), t);
    }
}
