package com.triples.rougether.userapi.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.notification.dto.NotificationSettingResponse;
import com.triples.rougether.userapi.notification.dto.NotificationSettingUpdateRequest;
import com.triples.rougether.userapi.notification.service.NotificationSettingService;
import com.triples.rougether.userapi.notification.web.NotificationSettingController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NotificationSettingController.class)
@AutoConfigureMockMvc(addFilters = false)
class NotificationSettingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationSettingService notificationSettingService;

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
    void 설정_조회_응답_계약() throws Exception {
        authAsUser7();
        when(notificationSettingService.getSettings(7L))
                .thenReturn(new NotificationSettingResponse(true, true, false));

        mockMvc.perform(get("/api/v1/users/me/notification-settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.all").value(true))
                .andExpect(jsonPath("$.reminder").value(true))
                .andExpect(jsonPath("$.house").value(false));
    }

    @Test
    void 부분_변경은_보낸_필드만_서비스로_전달되고_전체_설정을_응답한다() throws Exception {
        authAsUser7();
        when(notificationSettingService.updateSettings(7L, new NotificationSettingUpdateRequest(null, null, false)))
                .thenReturn(new NotificationSettingResponse(true, true, false));

        mockMvc.perform(patch("/api/v1/users/me/notification-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"house\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.all").value(true))
                .andExpect(jsonPath("$.reminder").value(true))
                .andExpect(jsonPath("$.house").value(false));

        verify(notificationSettingService).updateSettings(7L, new NotificationSettingUpdateRequest(null, null, false));
    }

    @Test
    void 세_필드가_모두_없으면_400이고_서비스를_호출하지_않는다() throws Exception {
        authAsUser7();

        mockMvc.perform(patch("/api/v1/users/me/notification-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(notificationSettingService, never()).updateSettings(any(), any());
    }

    @Test
    void 명시적_null만_보내도_400() throws Exception {
        authAsUser7();

        mockMvc.perform(patch("/api/v1/users/me/notification-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"all\": null, \"reminder\": null, \"house\": null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(notificationSettingService, never()).updateSettings(any(), any());
    }
}
