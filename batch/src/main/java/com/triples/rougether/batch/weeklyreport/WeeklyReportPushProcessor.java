package com.triples.rougether.batch.weeklyreport;

import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.report.WeeklyReportStats;
import com.triples.rougether.domain.report.entity.WeeklyReport;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// 회고 1건 → PENDING 알림 1건(refId=회고 id). 본문 수치는 statsJson 을 파싱해 넣는다.
// 중복(이미 알림 있는 회고)은 reader 쿼리의 not exists 가 걸러 여기 오지 않는다.
// statsJson 파싱 실패는 skip 정책으로 넘어가 그 사용자만 빠진다(스텝의 skip listener 가 로그로 남김)
@Component
class WeeklyReportPushProcessor implements ItemProcessor<WeeklyReport, Notification> {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Override
    public Notification process(WeeklyReport report) {
        WeeklyReportStats stats = objectMapper.readValue(report.getStatsJson(), WeeklyReportStats.class);
        return Notification.create(report.getUser(), NotificationType.WEEKLY_REPORT,
                WeeklyReportPushMessage.TITLE, WeeklyReportPushMessage.body(stats), report.getId());
    }
}
