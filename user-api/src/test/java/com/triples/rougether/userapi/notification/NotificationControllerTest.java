package com.triples.rougether.userapi.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.notification.dto.NotificationListResponse;
import com.triples.rougether.userapi.notification.dto.NotificationListResponse.NotificationItem;
import com.triples.rougether.userapi.notification.error.NotificationErrorCode;
import com.triples.rougether.userapi.notification.service.NotificationCommandService;
import com.triples.rougether.userapi.notification.service.NotificationQueryService;
import com.triples.rougether.userapi.notification.web.NotificationController;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NotificationController.class)
@AutoConfigureMockMvc(addFilters = false)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationQueryService notificationQueryService;

    @MockitoBean
    private NotificationCommandService notificationCommandService;

    @MockitoBean
    private CurrentUserArgumentResolver currentUserArgumentResolver;

    // security 컨텍스트의 JwtAuthenticationFilter 가 의존 — slice 테스트에서 mock 필요.
    @MockitoBean
    private TokenService tokenService;

    private void authAsUser7() {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new AuthUser(7L, null));
    }

    @Test
    void 알림_목록_응답_계약() throws Exception {
        authAsUser7();
        when(notificationQueryService.getNotifications(7L, null, 20)).thenReturn(new NotificationListResponse(
                List.of(new NotificationItem(12L, NotificationType.ROUTINE_REMINDER, "루틴 리마인드",
                        "물 마시기 할 시간이에요", false, Instant.parse("2026-07-05T00:00:00Z"))),
                12L, true));

        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].notificationId").value(12))
                .andExpect(jsonPath("$.items[0].type").value("ROUTINE_REMINDER"))
                .andExpect(jsonPath("$.items[0].isRead").value(false))
                .andExpect(jsonPath("$.nextCursor").value(12))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    void size_상한을_넘으면_400() throws Exception {
        authAsUser7();

        mockMvc.perform(get("/api/v1/notifications").param("size", "51"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 개별_읽음은_204() throws Exception {
        authAsUser7();

        mockMvc.perform(patch("/api/v1/notifications/5/read"))
                .andExpect(status().isNoContent());
        verify(notificationCommandService).markRead(7L, 5L);
    }

    @Test
    void 타인_알림_읽음은_404_에러_계약() throws Exception {
        authAsUser7();
        doThrow(new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND))
                .when(notificationCommandService).markRead(7L, 5L);

        mockMvc.perform(patch("/api/v1/notifications/5/read"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
    }

    @Test
    void 전체_읽음은_204() throws Exception {
        authAsUser7();

        mockMvc.perform(patch("/api/v1/notifications/read-all"))
                .andExpect(status().isNoContent());
        verify(notificationCommandService).markAllRead(7L);
    }
}
