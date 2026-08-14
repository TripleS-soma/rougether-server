package com.triples.rougether.adminapi.bugreport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.bugreport.entity.BugReport;
import com.triples.rougether.domain.bugreport.entity.BugReportImage;
import com.triples.rougether.domain.bugreport.repository.BugReportImageRepository;
import com.triples.rougether.domain.bugreport.repository.BugReportRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

// 어드민 버그 제보 (#213) - 목록·상태 필터·상태 변경.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BugReportAdminTest {

    @Autowired MockMvc mockMvc;
    @Autowired BugReportRepository bugReportRepository;
    @Autowired BugReportImageRepository bugReportImageRepository;
    @Autowired UserRepository userRepository;
    @MockitoBean S3Client s3Client;

    private BugReport report;
    private BugReportImage screenshot;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.signUp("admin-bug@rougether.dev"));
        report = bugReportRepository.save(BugReport.submit(
                user, "테스트 제보", "내용입니다", "1.0.0", "Android 14"));
        screenshot = bugReportImageRepository.save(
                BugReportImage.of(report, "bug-reports/private.png", 0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 버그_제보_페이지가_렌더링된다() throws Exception {
        mockMvc.perform(get("/bug-reports"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Rougether Admin · 버그 제보")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 목록과_상태_필터가_동작한다() throws Exception {
        mockMvc.perform(get("/admin/bug-reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.title == '테스트 제보')].status").value("RECEIVED"))
                .andExpect(jsonPath("$.items[?(@.title == '테스트 제보')].userId").exists())
                .andExpect(jsonPath("$.items[?(@.title == '테스트 제보')].screenshotKeys").exists());

        mockMvc.perform(get("/admin/bug-reports").param("status", "RESOLVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.title == '테스트 제보')]").isEmpty());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 상태_변경과_유효성_검사() throws Exception {
        mockMvc.perform(patch("/admin/bug-reports/{id}/status", report.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"IN_PROGRESS\"}").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        mockMvc.perform(patch("/admin/bug-reports/{id}/status", report.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"NOPE\"}").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUG_REPORT_STATUS_INVALID"));

        mockMvc.perform(patch("/admin/bug-reports/{id}/status", report.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": null}").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUG_REPORT_STATUS_INVALID"));

        // 잘못된 status 필터도 공통 에러 형식으로 400
        mockMvc.perform(get("/admin/bug-reports").param("status", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUG_REPORT_STATUS_INVALID"));

        mockMvc.perform(patch("/admin/bug-reports/{id}/status", 999999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"RESOLVED\"}").with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BUG_REPORT_NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 스크린샷은_인증된_어드민_엔드포인트로만_조회한다() throws Exception {
        byte[] content = new byte[] {1, 2, 3};
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(
                        GetObjectResponse.builder().contentType("image/png").build(), content));

        mockMvc.perform(get("/admin/bug-reports/screenshots")
                        .param("key", screenshot.getStorageKey()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().contentType("image/png"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().bytes(content));

        verify(s3Client).getObjectAsBytes(argThat((GetObjectRequest request) ->
                "bug-reports/private.png".equals(request.key())));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 등록되지_않았거나_다른_prefix인_이미지는_조회하지_않는다() throws Exception {
        mockMvc.perform(get("/admin/bug-reports/screenshots")
                        .param("key", "items/public.png"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BUG_REPORT_SCREENSHOT_NOT_FOUND"));

        verify(s3Client, never()).getObjectAsBytes(any(GetObjectRequest.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void DB_row만_남고_S3_원본이_없으면_404다() throws Exception {
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());

        mockMvc.perform(get("/admin/bug-reports/screenshots")
                        .param("key", screenshot.getStorageKey()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BUG_REPORT_SCREENSHOT_NOT_FOUND"));
    }

    @Test
    void 미인증이면_접근_불가() throws Exception {
        mockMvc.perform(get("/admin/bug-reports"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/admin/bug-reports/screenshots")
                        .param("key", screenshot.getStorageKey()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(patch("/admin/bug-reports/{id}/status", report.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"RESOLVED\"}").with(csrf()))
                .andExpect(status().is3xxRedirection());
    }
}
