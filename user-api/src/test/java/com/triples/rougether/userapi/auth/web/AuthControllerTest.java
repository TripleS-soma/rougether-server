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
import com.triples.rougether.common.error.AuthErrorCode;
import com.triples.rougether.userapi.auth.dto.TokenResponse;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.Map;

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
        when(authService.kakaoLogin("kakao-access", false)).thenReturn(new LoginResponse(3L, "acc", "ref", true));

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
        when(authService.kakaoLogin("bad", false))
                .thenThrow(new BusinessException(AuthErrorCode.OAUTH_KAKAO_TOKEN_INVALID));

        mockMvc.perform(post("/api/v1/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessToken\":\"bad\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_OAUTH_KAKAO_TOKEN_INVALID"));
    }

    @Test
    void kakao_login_은_카카오_서버_오류면_502_와_code_를_준다() throws Exception {
        when(authService.kakaoLogin("tok", false))
                .thenThrow(new BusinessException(AuthErrorCode.OAUTH_KAKAO_UNAVAILABLE));

        mockMvc.perform(post("/api/v1/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessToken\":\"tok\"}"))
                .andExpect(status().is(502))
                .andExpect(jsonPath("$.code").value("AUTH_OAUTH_KAKAO_UNAVAILABLE"));
    }

    @Test
    void kakao_login_은_같은_이메일_타_provider_계정이_있으면_409_와_providers_를_준다() throws Exception {
        when(authService.kakaoLogin("kakao-access", false))
                .thenThrow(new BusinessException(AuthErrorCode.EMAIL_LINKED_TO_OTHER_PROVIDER,
                        "이 이메일은 애플 로그인으로 가입되어 있어요.", Map.of("providers", List.of("APPLE"))));

        mockMvc.perform(post("/api/v1/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessToken\":\"kakao-access\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_EMAIL_LINKED_TO_OTHER_PROVIDER"))
                .andExpect(jsonPath("$.message").value("이 이메일은 애플 로그인으로 가입되어 있어요."))
                .andExpect(jsonPath("$.details.providers[0]").value("APPLE"));
    }

    @Test
    void kakao_login_의_allowNewAccount_는_서비스에_전달된다() throws Exception {
        when(authService.kakaoLogin("kakao-access", true)).thenReturn(new LoginResponse(3L, "acc", "ref", true));

        mockMvc.perform(post("/api/v1/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessToken\":\"kakao-access\",\"allowNewAccount\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isNewUser").value(true));

        verify(authService).kakaoLogin("kakao-access", true);
    }

    @Test
    void google_apple_login_의_allowNewAccount_도_서비스에_전달된다() throws Exception {
        when(authService.googleLogin("google-id", true)).thenReturn(new LoginResponse(5L, "acc", "ref", true));
        when(authService.appleLogin("apple-id", "authcode", true)).thenReturn(new LoginResponse(7L, "acc", "ref", true));

        mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"google-id\",\"allowNewAccount\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"apple-id\",\"authorizationCode\":\"authcode\",\"allowNewAccount\":true}"))
                .andExpect(status().isOk());

        verify(authService).googleLogin("google-id", true);
        verify(authService).appleLogin("apple-id", "authcode", true);
    }

    @Test
    void google_login_성공_응답_계약() throws Exception {
        when(authService.googleLogin("google-id", false)).thenReturn(new LoginResponse(5L, "acc", "ref", true));

        mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"google-id\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(5))
                .andExpect(jsonPath("$.accessToken").value("acc"))
                .andExpect(jsonPath("$.refreshToken").value("ref"))
                .andExpect(jsonPath("$.isNewUser").value(true));
    }

    @Test
    void google_login_은_idToken_이_없으면_400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void google_login_은_구글_토큰이_무효면_401_과_code_를_준다() throws Exception {
        when(authService.googleLogin("bad", false))
                .thenThrow(new BusinessException(AuthErrorCode.OAUTH_GOOGLE_TOKEN_INVALID));

        mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"bad\",\"authorizationCode\":\"authcode\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_OAUTH_GOOGLE_TOKEN_INVALID"));
    }

    @Test
    void google_login_은_구글_서버_오류면_502_와_code_를_준다() throws Exception {
        when(authService.googleLogin("tok", false))
                .thenThrow(new BusinessException(AuthErrorCode.OAUTH_GOOGLE_UNAVAILABLE));

        mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"tok\"}"))
                .andExpect(status().is(502))
                .andExpect(jsonPath("$.code").value("AUTH_OAUTH_GOOGLE_UNAVAILABLE"));
    }

    @Test
    void apple_login_성공_응답_계약() throws Exception {
        when(authService.appleLogin("apple-id", "authcode", false)).thenReturn(new LoginResponse(7L, "acc", "ref", true));

        mockMvc.perform(post("/api/v1/auth/apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"apple-id\",\"authorizationCode\":\"authcode\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.accessToken").value("acc"))
                .andExpect(jsonPath("$.refreshToken").value("ref"))
                .andExpect(jsonPath("$.isNewUser").value(true));
    }

    @Test
    void apple_login_은_idToken_이_없으면_400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void apple_login_은_idToken_이_공백이면_400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"  \",\"authorizationCode\":\"authcode\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void apple_login_은_authorizationCode_가_없으면_400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"apple-id\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void apple_login_은_애플_토큰이_무효면_401_과_code_를_준다() throws Exception {
        when(authService.appleLogin("bad", "authcode", false))
                .thenThrow(new BusinessException(AuthErrorCode.OAUTH_APPLE_TOKEN_INVALID));

        mockMvc.perform(post("/api/v1/auth/apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"bad\",\"authorizationCode\":\"authcode\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_OAUTH_APPLE_TOKEN_INVALID"));
    }

    @Test
    void apple_login_은_애플_서버_오류면_502_와_code_를_준다() throws Exception {
        when(authService.appleLogin("tok", "authcode", false))
                .thenThrow(new BusinessException(AuthErrorCode.OAUTH_APPLE_UNAVAILABLE));

        mockMvc.perform(post("/api/v1/auth/apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"tok\",\"authorizationCode\":\"authcode\"}"))
                .andExpect(status().is(502))
                .andExpect(jsonPath("$.code").value("AUTH_OAUTH_APPLE_UNAVAILABLE"));
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
