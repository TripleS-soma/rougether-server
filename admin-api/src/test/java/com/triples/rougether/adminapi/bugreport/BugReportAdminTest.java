package com.triples.rougether.adminapi.bugreport;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.bugreport.entity.BugReport;
import com.triples.rougether.domain.bugreport.repository.BugReportRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

// 어드민 버그 제보 (#213) - 목록·상태 필터·상태 변경.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BugReportAdminTest {

    @Autowired MockMvc mockMvc;
    @Autowired BugReportRepository bugReportRepository;
    @Autowired UserRepository userRepository;

    private BugReport report;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.signUp("admin-bug@rougether.dev"));
        report = bugReportRepository.save(BugReport.submit(
                user, "테스트 제보", "내용입니다", "1.0.0", "Android 14"));
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
    void 미인증이면_접근_불가() throws Exception {
        mockMvc.perform(get("/admin/bug-reports"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(patch("/admin/bug-reports/{id}/status", report.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"RESOLVED\"}").with(csrf()))
                .andExpect(status().is3xxRedirection());
    }
}
