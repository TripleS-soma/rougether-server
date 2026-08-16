package com.triples.rougether.userapi.report.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.report.WeeklyReportSections;
import com.triples.rougether.domain.report.WeeklyReportStats;
import com.triples.rougether.domain.report.entity.WeeklyReport;
import com.triples.rougether.domain.report.repository.WeeklyReportRepository;
import com.triples.rougether.userapi.report.dto.WeeklyReportDetailResponse;
import com.triples.rougether.userapi.report.dto.WeeklyReportListResponse;
import com.triples.rougether.userapi.report.dto.WeeklyReportSummaryItem;
import com.triples.rougether.userapi.report.error.WeeklyReportErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

// 내 주간 회고 조회. 회고는 배치(#286)가 만들고 여기서는 읽기만 한다 — 생성·재생성 API 없음.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WeeklyReportQueryService {

    private final WeeklyReportRepository weeklyReportRepository;
    private final ObjectMapper objectMapper;

    public WeeklyReportListResponse getMyReports(Long userId) {
        List<WeeklyReportSummaryItem> items = weeklyReportRepository.findByUserIdOrderByWeekStartDateDesc(userId)
                .stream()
                .map(report -> WeeklyReportSummaryItem.of(report, readStats(report)))
                .toList();
        return new WeeklyReportListResponse(items);
    }

    // 소유권 guard: userId 로 함께 조회해 타인 회고는 존재 여부와 무관하게 404.
    public WeeklyReportDetailResponse getMyReport(Long userId, Long reportId) {
        WeeklyReport report = weeklyReportRepository.findByIdAndUserId(reportId, userId)
                .orElseThrow(() -> new BusinessException(WeeklyReportErrorCode.WEEKLY_REPORT_NOT_FOUND));
        return WeeklyReportDetailResponse.of(report, readStats(report), readSections(report));
    }

    private WeeklyReportStats readStats(WeeklyReport report) {
        return objectMapper.readValue(report.getStatsJson(), WeeklyReportStats.class);
    }

    private WeeklyReportSections readSections(WeeklyReport report) {
        return objectMapper.readValue(report.getSectionsJson(), WeeklyReportSections.class);
    }
}
