package com.triples.rougether.domain.report.repository;

import com.triples.rougether.domain.report.entity.WeeklyReportStatus;
import java.time.Instant;
import java.time.LocalDate;

// 관리자 AI 회고 관측용 주차·상태·모델별 집계 projection. 회고 본문은 싣지 않고 건수만 전달함.
public interface WeeklyReportStatusCount {

    LocalDate getWeekStartDate();

    WeeklyReportStatus getStatus();

    String getModel();

    long getReportCount();

    // 그룹 내 열람(viewed_at 기록) 건수(#332)
    long getViewedCount();

    Instant getLastGeneratedAt();
}
