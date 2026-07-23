package com.triples.rougether.userapi.bugreport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.bugreport.entity.BugReportStatus;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.bugreport.dto.BugReportListResponse;
import com.triples.rougether.userapi.bugreport.dto.BugReportResponse;
import com.triples.rougether.userapi.bugreport.service.BugReportService;
import com.triples.rougether.userapi.bugreport.web.BugReportController;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 버그 제보 컨트롤러 계약 (#213) - multipart 바인딩·validation 경계.
@WebMvcTest(BugReportController.class)
@AutoConfigureMockMvc(addFilters = false)
class BugReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BugReportService bugReportService;

    @MockitoBean
    private CurrentUserArgumentResolver currentUserArgumentResolver;

    // security 컨텍스트의 JwtAuthenticationFilter 가 의존 — slice 테스트에서 mock 필요.
    @MockitoBean
    private TokenService tokenService;

    private void authAsUser7() throws Exception {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new AuthUser(7L, null));
    }

    @Test
    void 제출은_201_이고_응답_계약을_지킨다() throws Exception {
        authAsUser7();
        when(bugReportService.submit(eq(7L), eq("버그 제목"), eq("버그 내용"), eq("1.0.0"), eq(null), any()))
                .thenReturn(new BugReportResponse(1L, "버그 제목", "버그 내용",
                        BugReportStatus.RECEIVED, List.of("bug-reports/a.png"),
                        Instant.parse("2026-07-23T00:00:00Z")));

        mockMvc.perform(multipart("/api/v1/bug-reports")
                        .file(new MockMultipartFile("images", "a.png", "image/png", new byte[]{1}))
                        .param("title", "버그 제목")
                        .param("content", "버그 내용")
                        .param("appVersion", "1.0.0"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bugReportId").value(1))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.screenshotKeys[0]").value("bug-reports/a.png"));
    }

    @Test
    void 제목_101자_내용_2001자_빈_제목은_400() throws Exception {
        authAsUser7();
        mockMvc.perform(multipart("/api/v1/bug-reports")
                        .param("title", "가".repeat(101))
                        .param("content", "내용"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart("/api/v1/bug-reports")
                        .param("title", "제목")
                        .param("content", "가".repeat(2001)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart("/api/v1/bug-reports")
                        .param("title", "  ")
                        .param("content", "내용"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 내_제보_목록_응답_계약() throws Exception {
        authAsUser7();
        when(bugReportService.getMyReports(7L)).thenReturn(new BugReportListResponse(List.of(
                new BugReportResponse(2L, "t", "c", BugReportStatus.IN_PROGRESS, List.of(),
                        Instant.parse("2026-07-23T00:00:00Z")))));

        mockMvc.perform(get("/api/v1/me/bug-reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].bugReportId").value(2))
                .andExpect(jsonPath("$.items[0].status").value("IN_PROGRESS"));
    }
}
