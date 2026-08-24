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
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

// 내 주간 회고 조회. 회고는 배치(#286)가 만들고 여기서는 읽기만 한다 — 생성·재생성 API 없음.
// 예외로 상세 조회는 최초 열람 시각(viewed_at, #332)을 기록한다 — 조회가 곧 열람 이벤트라 프론트 협조 없이 측정된다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WeeklyReportQueryService {

    private final WeeklyReportRepository weeklyReportRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WeeklyReportListResponse getMyReports(Long userId) {
        List<WeeklyReportSummaryItem> items = weeklyReportRepository.findByUserIdOrderByWeekStartDateDesc(userId)
                .stream()
                .map(report -> WeeklyReportSummaryItem.of(report, readStats(report)))
                .toList();
        return new WeeklyReportListResponse(items);
    }

    // 소유권 guard: userId 로 함께 조회해 타인 회고는 존재 여부와 무관하게 404.
    // 쓰기 트랜잭션(메서드 레벨 override): 최초 열람이면 viewed_at 을 dirty checking 으로 기록하고 응답에도 반영된다.
    // 동시 최초 조회는 거의 같은 시각을 쓰므로 잃어도 무해해 별도 잠금 없음.
    @Transactional
    public WeeklyReportDetailResponse getMyReport(Long userId, Long reportId) {
        WeeklyReport report = weeklyReportRepository.findByIdAndUserId(reportId, userId)
                .orElseThrow(() -> new BusinessException(WeeklyReportErrorCode.WEEKLY_REPORT_NOT_FOUND));
        // TIMESTAMP 컬럼은 초 단위라 응답과 저장값이 어긋나지 않게 초로 절삭해 기록함
        report.markViewed(Instant.now(clock).truncatedTo(ChronoUnit.SECONDS));
        return WeeklyReportDetailResponse.of(report, readStats(report), readSections(report));
    }

    private WeeklyReportStats readStats(WeeklyReport report) {
        return objectMapper.readValue(report.getStatsJson(), WeeklyReportStats.class);
    }

    private WeeklyReportSections readSections(WeeklyReport report) {
        return objectMapper.readValue(report.getSectionsJson(), WeeklyReportSections.class);
    }
}
