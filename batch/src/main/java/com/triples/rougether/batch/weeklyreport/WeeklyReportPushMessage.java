package com.triples.rougether.batch.weeklyreport;

import com.triples.rougether.domain.report.WeeklyReportStats;

// 주간 회고 push 문구. 본문 수치는 statsJson(서버 집계 정본)만 쓴다 -
// LLM 이 만든 summary 는 길이·내용 통제가 안 되므로 push 본문에 넣지 않는다(결정값)
final class WeeklyReportPushMessage {

    static final String TITLE = "지난주 루틴 회고가 도착했어요";
    private static final String BODY_FORMAT = "지난주 루틴 %d회 중 %d회를 완료했어요. 이번 주 계획 전에 확인해 보세요.";

    private WeeklyReportPushMessage() {
    }

    static String body(WeeklyReportStats stats) {
        return BODY_FORMAT.formatted(stats.scheduledCount(), stats.completedCount());
    }
}
