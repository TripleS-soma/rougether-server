package com.triples.rougether.userapi.report;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.report.entity.WeeklyReportStatus;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.report.dto.WeeklyReportDetailResponse;
import com.triples.rougether.userapi.report.dto.WeeklyReportListResponse;
import com.triples.rougether.userapi.report.dto.WeeklyReportSummaryItem;
import com.triples.rougether.userapi.report.dto.WeeklyStatsResponse;
import com.triples.rougether.userapi.report.error.WeeklyReportErrorCode;
import com.triples.rougether.userapi.report.service.WeeklyReportQueryService;
import com.triples.rougether.userapi.report.web.WeeklyReportController;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WeeklyReportController.class)
@AutoConfigureMockMvc(addFilters = false)
class WeeklyReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WeeklyReportQueryService weeklyReportQueryService;

    @MockitoBean
    private CurrentUserArgumentResolver currentUserArgumentResolver;

    // security 컨텍스트의 JwtAuthenticationFilter 가 의존 — slice 테스트에서 mock 필요.
    @MockitoBean
    private TokenService tokenService;

    private void authAsUser1() {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new AuthUser(1L, null));
    }

    @Test
    void 목록_응답_계약() throws Exception {
        authAsUser1();
        WeeklyReportSummaryItem item = new WeeklyReportSummaryItem(7L, LocalDate.of(2026, 8, 9),
                LocalDate.of(2026, 8, 15), WeeklyReportStatus.GENERATED, 0.67, 2, 3, "요약",
                Instant.parse("2026-08-16T00:30:00Z"), Instant.parse("2026-08-16T11:00:00Z"));
        when(weeklyReportQueryService.getMyReports(1L)).thenReturn(new WeeklyReportListResponse(List.of(item)));

        mockMvc.perform(get("/api/v1/reports/weekly"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].reportId").value(7))
                .andExpect(jsonPath("$.items[0].weekStartDate").value("2026-08-09"))
                .andExpect(jsonPath("$.items[0].weekEndDate").value("2026-08-15"))
                .andExpect(jsonPath("$.items[0].status").value("GENERATED"))
                .andExpect(jsonPath("$.items[0].completionRate").value(0.67))
                .andExpect(jsonPath("$.items[0].completedCount").value(2))
                .andExpect(jsonPath("$.items[0].scheduledCount").value(3))
                .andExpect(jsonPath("$.items[0].summary").value("요약"))
                .andExpect(jsonPath("$.items[0].generatedAt").value("2026-08-16T00:30:00Z"))
                .andExpect(jsonPath("$.items[0].viewedAt").value("2026-08-16T11:00:00Z"));
    }

    @Test
    void 회고가_없으면_빈_items_200() throws Exception {
        authAsUser1();
        when(weeklyReportQueryService.getMyReports(1L)).thenReturn(new WeeklyReportListResponse(List.of()));

        mockMvc.perform(get("/api/v1/reports/weekly"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void 상세_응답_계약() throws Exception {
        authAsUser1();
        WeeklyStatsResponse stats = new WeeklyStatsResponse(3, 2, 1, 0.67,
                List.of(new WeeklyStatsResponse.WeekdayStatResponse(DayOfWeek.SUNDAY, 1, 0)),
                List.of(new WeeklyStatsResponse.RoutineStatResponse(4L, "달리기", "건강", 2, 1)),
                new WeeklyStatsResponse.StreakResponse(2, 5));
        WeeklyReportDetailResponse detail = new WeeklyReportDetailResponse(7L, LocalDate.of(2026, 8, 9),
                LocalDate.of(2026, 8, 15), WeeklyReportStatus.GENERATED, 0.67, 2, 3, "요약",
                Instant.parse("2026-08-16T00:30:00Z"), Instant.parse("2026-08-16T11:00:00Z"), stats,
                List.of("잘한 점"), List.of("실패 패턴"), List.of("제안"));
        when(weeklyReportQueryService.getMyReport(1L, 7L)).thenReturn(detail);

        mockMvc.perform(get("/api/v1/reports/weekly/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportId").value(7))
                .andExpect(jsonPath("$.status").value("GENERATED"))
                .andExpect(jsonPath("$.viewedAt").value("2026-08-16T11:00:00Z"))
                .andExpect(jsonPath("$.stats.scheduledCount").value(3))
                .andExpect(jsonPath("$.stats.failedCount").value(1))
                .andExpect(jsonPath("$.stats.byWeekday[0].dayOfWeek").value("SUNDAY"))
                .andExpect(jsonPath("$.stats.byWeekday[0].completed").value(1))
                .andExpect(jsonPath("$.stats.byRoutine[0].lineageId").value(4))
                .andExpect(jsonPath("$.stats.byRoutine[0].title").value("달리기"))
                .andExpect(jsonPath("$.stats.byRoutine[0].categoryName").value("건강"))
                .andExpect(jsonPath("$.stats.streak.currentCount").value(2))
                .andExpect(jsonPath("$.stats.streak.longestCount").value(5))
                .andExpect(jsonPath("$.highlights[0]").value("잘한 점"))
                .andExpect(jsonPath("$.failurePatterns[0]").value("실패 패턴"))
                .andExpect(jsonPath("$.suggestions[0]").value("제안"));
    }

    @Test
    void 없는_회고는_404_WEEKLY_REPORT_NOT_FOUND() throws Exception {
        authAsUser1();
        when(weeklyReportQueryService.getMyReport(1L, 99L))
                .thenThrow(new BusinessException(WeeklyReportErrorCode.WEEKLY_REPORT_NOT_FOUND));

        mockMvc.perform(get("/api/v1/reports/weekly/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WEEKLY_REPORT_NOT_FOUND"));
    }
}
