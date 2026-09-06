package com.triples.rougether.userapi.appicon;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.appicon.AppIconState;
import com.triples.rougether.userapi.appicon.dto.AppIconResponse;
import com.triples.rougether.userapi.appicon.service.AppIconService;
import com.triples.rougether.userapi.appicon.web.AppIconController;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AppIconController.class)
@AutoConfigureMockMvc(addFilters = false)
class AppIconControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AppIconService appIconService;
    @MockitoBean private CurrentUserArgumentResolver currentUserArgumentResolver;
    @MockitoBean private TokenService tokenService;

    @BeforeEach
    void authenticate() {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new AuthUser(7L, null));
    }

    @Test
    void 상태_조회는_시각과_문구를_반환하고_활동은_기록하지_않는다() throws Exception {
        Instant now = Instant.parse("2026-09-06T03:00:00Z");
        when(appIconService.get(7L)).thenReturn(new AppIconResponse(
                AppIconState.MISSING_YOU, "요즘 좀 뜸하다냥…", now,
                now.minusSeconds(172800), now.plusSeconds(172800), 0, false));

        mockMvc.perform(get("/api/v1/me/app-icon"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("MISSING_YOU"))
                .andExpect(jsonPath("$.message").value("요즘 좀 뜸하다냥…"))
                .andExpect(jsonPath("$.evaluatedAt").value("2026-09-06T03:00:00Z"))
                .andExpect(jsonPath("$.lastForegroundAt").value("2026-09-04T03:00:00Z"))
                .andExpect(jsonPath("$.nextEvaluationAt").value("2026-09-08T03:00:00Z"))
                .andExpect(jsonPath("$.currentStreak").value(0))
                .andExpect(jsonPath("$.completedToday").value(false));
        verify(appIconService, never()).recordForeground(any());
    }

    @Test
    void foreground는_본문_없이_인증된_사용자의_갱신_결과를_반환한다() throws Exception {
        Instant now = Instant.parse("2026-09-06T03:00:00Z");
        when(appIconService.recordForeground(7L)).thenReturn(new AppIconResponse(
                AppIconState.STREAK_CHAMPION, "꾸준함의 왕이다냥!", now, now,
                Instant.parse("2026-09-06T15:00:00Z"), 7, true));

        mockMvc.perform(post("/api/v1/me/app-activity"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("STREAK_CHAMPION"))
                .andExpect(jsonPath("$.currentStreak").value(7))
                .andExpect(jsonPath("$.completedToday").value(true));
        verify(appIconService).recordForeground(7L);
    }

    @Test
    void 사용할_수_없는_계정은_공통_에러_계약을_따른다() throws Exception {
        when(appIconService.get(7L)).thenThrow(new BusinessException(MemberErrorCode.USER_NOT_FOUND));

        mockMvc.perform(get("/api/v1/me/app-icon"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }
}
