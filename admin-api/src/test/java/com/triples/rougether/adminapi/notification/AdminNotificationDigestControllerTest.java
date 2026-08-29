package com.triples.rougether.adminapi.notification;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.adminapi.notification.dto.AdminNotificationDigestMetricsResponse;
import com.triples.rougether.adminapi.notification.dto.AdminNotificationDigestMetricsResponse.DayMetric;
import com.triples.rougether.adminapi.notification.service.AdminNotificationDigestMetricsService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AdminNotificationDigestControllerTest {

    private static final Instant GENERATED_AT = Instant.parse("2030-09-02T03:00:00Z");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AdminNotificationDigestMetricsService metricsService;

    @Test
    void 미인증이면_관측_API_에_접근할_수_없다() throws Exception {
        mockMvc.perform(get("/admin/notification-digests/metrics"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void 일반_사용자는_관측_API_에_접근할_수_없다() throws Exception {
        mockMvc.perform(get("/admin/notification-digests/metrics")
                        .with(user("member").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 관리자는_digest_관측_JSON_을_조회한다() throws Exception {
        when(metricsService.getMetrics(7)).thenReturn(new AdminNotificationDigestMetricsResponse(List.of(
                DayMetric.of(LocalDate.of(2030, 9, 2), 2, 0, 1, 1, 0, 0, 0, 1)
        ), GENERATED_AT));

        mockMvc.perform(get("/admin/notification-digests/metrics")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(1))
                .andExpect(jsonPath("$.days[0].date").value("2030-09-02"))
                .andExpect(jsonPath("$.days[0].createdCount").value(2))
                .andExpect(jsonPath("$.days[0].sentCount").value(1))
                .andExpect(jsonPath("$.days[0].blockedCount").value(1))
                .andExpect(jsonPath("$.days[0].conversionPendingCount").value(1))
                .andExpect(jsonPath("$.generatedAt").value(GENERATED_AT.toString()));
    }

    @Test
    void 조회_일수는_1_이상_90_이하만_허용한다() throws Exception {
        mockMvc.perform(get("/admin/notification-digests/metrics")
                        .param("days", "0")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/admin/notification-digests/metrics")
                        .param("days", "91")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 조회_일수가_숫자가_아니면_공통_validation_error를_반환한다() throws Exception {
        mockMvc.perform(get("/admin/notification-digests/metrics")
                        .param("days", "abc")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("입력값이 올바르지 않습니다."));
    }
}
