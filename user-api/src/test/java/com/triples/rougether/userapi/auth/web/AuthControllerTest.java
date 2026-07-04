package com.triples.rougether.userapi.auth.web;

import com.triples.rougether.userapi.auth.service.AuthService;
import com.triples.rougether.userapi.auth.service.TokenService;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.auth.dto.LoginResponse;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import com.triples.rougether.userapi.auth.dto.TokenResponse;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private TokenService tokenService;
    @MockitoBean
    private CurrentUserArgumentResolver currentUserArgumentResolver;

    @Test
    void dev_login_성공_응답_계약() throws Exception {
        when(authService.devLogin(7L)).thenReturn(new LoginResponse(7L, "acc", "ref", false));

        mockMvc.perform(post("/api/v1/auth/dev-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.accessToken").value("acc"))
                .andExpect(jsonPath("$.refreshToken").value("ref"))
                .andExpect(jsonPath("$.isNewUser").value(false));
    }

    @Test
    void kakao_login_성공_응답_계약() throws Exception {
        when(authService.kakaoLogin("kakao-access")).thenReturn(new LoginResponse(3L, "acc", "ref", true));

        mockMvc.perform(post("/api/v1/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessToken\":\"kakao-access\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(3))
                .andExpect(jsonPath("$.accessToken").value("acc"))
                .andExpect(jsonPath("$.refreshToken").value("ref"))
                .andExpect(jsonPath("$.isNewUser").value(true));
    }

    @Test
    void kakao_login_은_accessToken_이_없으면_400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void kakao_login_은_카카오_토큰이_무효면_401_과_code_를_준다() throws Exception {
        when(authService.kakaoLogin("bad"))
                .thenThrow(new BusinessException(AuthErrorCode.OAUTH_KAKAO_TOKEN_INVALID));

        mockMvc.perform(post("/api/v1/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessToken\":\"bad\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_OAUTH_KAKAO_TOKEN_INVALID"));
    }

    @Test
    void kakao_login_은_카카오_서버_오류면_502_와_code_를_준다() throws Exception {
        when(authService.kakaoLogin("tok"))
                .thenThrow(new BusinessException(AuthErrorCode.OAUTH_KAKAO_UNAVAILABLE));

        mockMvc.perform(post("/api/v1/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessToken\":\"tok\"}"))
                .andExpect(status().is(502))
                .andExpect(jsonPath("$.code").value("AUTH_OAUTH_KAKAO_UNAVAILABLE"));
    }

    @Test
    void refresh_성공_응답_계약() throws Exception {
        when(authService.refresh("rt")).thenReturn(new TokenResponse("new-acc", "new-ref"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"rt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-acc"))
                .andExpect(jsonPath("$.refreshToken").value("new-ref"));
    }

    @Test
    void logout_은_204_이고_서비스를_호출한다() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"rt\"}"))
                .andExpect(status().isNoContent());

        verify(authService).logout("rt");
    }
}
