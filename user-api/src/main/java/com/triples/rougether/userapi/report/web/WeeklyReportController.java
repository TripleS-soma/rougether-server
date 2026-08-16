package com.triples.rougether.userapi.report.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.report.dto.WeeklyReportDetailResponse;
import com.triples.rougether.userapi.report.dto.WeeklyReportListResponse;
import com.triples.rougether.userapi.report.service.WeeklyReportQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Report", description = "AI 주간 회고 관련 API")
@RestController
@RequestMapping("/api/v1/reports/weekly")
@RequiredArgsConstructor
public class WeeklyReportController {

    private final WeeklyReportQueryService weeklyReportQueryService;

    @Operation(summary = "주간 회고 목록 조회",
            description = "내 AI 주간 회고를 최신 주부터(weekStartDate 내림차순) 모두 반환합니다. "
                    + "회고는 매주 일요일 00:30(KST)에 배치가 직전 일~토 주의 루틴 수행 기록으로 생성하며, "
                    + "그 주에 루틴 완료/실패 기록이 하나도 없으면 회고를 만들지 않습니다. 회고가 없으면 빈 items를 반환합니다.")
    @GetMapping
    public WeeklyReportListResponse getMyReports(@CurrentUser AuthUser user) {
        return weeklyReportQueryService.getMyReports(user.id());
    }

    @Operation(summary = "주간 회고 상세 조회",
            description = "내 주간 회고 한 건을 통계(요일별·루틴별·스트릭)와 AI 섹션(잘한 점·실패 패턴·제안)까지 반환합니다. "
                    + "본인 회고만 조회할 수 있습니다. status가 FALLBACK이면 AI 섹션 배열은 비어 있고 통계와 요약 문구만 제공됩니다.")
    @GetMapping("/{reportId}")
    public WeeklyReportDetailResponse getMyReport(
            @CurrentUser AuthUser user,
            @Parameter(description = "회고 ID. 주간 회고 목록 조회(GET /api/v1/reports/weekly) 응답의 reportId 값")
            @PathVariable Long reportId) {
        return weeklyReportQueryService.getMyReport(user.id(), reportId);
    }
}
