package com.triples.rougether.userapi.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.member.repository.RefreshTokenRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.auth.client.KakaoApiClient;
import com.triples.rougether.userapi.auth.client.KakaoUser;
import com.triples.rougether.userapi.auth.dto.LoginResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.OauthProvider;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;

// kakaoLogin 오케스트레이션(카카오 조회 → 핸들러 위임 → 경쟁 충돌 시 재시도)만 검증함.
// find-or-create·영속 결과는 KakaoLoginIntegrationTest가 실제 DB로 검증함.
@ExtendWith(MockitoExtension.class)
class AuthServiceKakaoLoginTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private TokenService tokenService;
    @Mock
    private KakaoApiClient kakaoApiClient;
    @Mock
    private KakaoLoginHandler kakaoLoginHandler;
    @Mock
    private com.triples.rougether.userapi.auth.client.GoogleTokenVerifier googleTokenVerifier;
    @Mock
    private GoogleLoginHandler googleLoginHandler;
    @Mock
    private com.triples.rougether.userapi.auth.client.AppleTokenVerifier appleTokenVerifier;
    @Mock
    private AppleLoginHandler appleLoginHandler;
    @Mock
    private com.triples.rougether.userapi.auth.client.AppleTokenExchangeClient appleTokenExchangeClient;
    @Mock
    private AppleRefreshTokenCipher appleRefreshTokenCipher;

    @Mock
    private SignupService signupService;
    @Mock
    private EmailProviderConflictGuard emailProviderConflictGuard;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository, refreshTokenRepository, tokenService,
                new RefreshTokenReuseGuard(refreshTokenRepository), kakaoApiClient, kakaoLoginHandler,
                googleTokenVerifier, googleLoginHandler, appleTokenVerifier, appleLoginHandler,
                appleTokenExchangeClient, appleRefreshTokenCipher, signupService, emailProviderConflictGuard);
    }

    @Test
    void 카카오_조회_결과로_핸들러에_위임하고_응답을_그대로_반환한다() {
        KakaoUser kakaoUser = new KakaoUser("kakao-1", "a@b.com");
        LoginResponse expected = new LoginResponse(10L, "acc", "ref", true);
        when(kakaoApiClient.fetchUser("tok")).thenReturn(kakaoUser);
        when(kakaoLoginHandler.login(kakaoUser)).thenReturn(expected);

        LoginResponse response = authService.kakaoLogin("tok");

        assertThat(response).isEqualTo(expected);
        verify(kakaoLoginHandler, times(1)).login(kakaoUser);
    }

    @Test
    void 동시_최초가입_경쟁에서_unique_충돌이_나면_새_트랜잭션으로_재시도해_로그인으로_전환한다() {
        KakaoUser kakaoUser = new KakaoUser("kakao-2", "a@b.com");
        LoginResponse afterRetry = new LoginResponse(9L, "acc", "ref", false);
        when(kakaoApiClient.fetchUser("tok")).thenReturn(kakaoUser);
        // 첫 시도는 경쟁 충돌로 롤백, 재시도는 승자 연동으로 로그인 성공.
        when(kakaoLoginHandler.login(kakaoUser))
                .thenThrow(new DataIntegrityViolationException("uq_oauth_provider_user"))
                .thenReturn(afterRetry);

        LoginResponse response = authService.kakaoLogin("tok");

        assertThat(response.isNewUser()).isFalse();
        assertThat(response.userId()).isEqualTo(9L);
        verify(kakaoLoginHandler, times(2)).login(kakaoUser);
    }

    @Test
    void 같은_이메일의_타_provider_계정_안내_409면_핸들러를_호출하지_않는다() {
        KakaoUser kakaoUser = new KakaoUser("kakao-3", "a@b.com", true);
        when(kakaoApiClient.fetchUser("tok")).thenReturn(kakaoUser);
        doThrow(new BusinessException(AuthErrorCode.EMAIL_LINKED_TO_OTHER_PROVIDER))
                .when(emailProviderConflictGuard)
                .ensureNewAccountAllowed(OauthProvider.KAKAO, "kakao-3", "a@b.com", true, false);

        assertThatThrownBy(() -> authService.kakaoLogin("tok"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.EMAIL_LINKED_TO_OTHER_PROVIDER);
        verify(kakaoLoginHandler, never()).login(any());
    }

    @Test
    void allowNewAccount_는_가드에_그대로_전달되고_가입이_진행된다() {
        KakaoUser kakaoUser = new KakaoUser("kakao-4", "a@b.com", true);
        LoginResponse created = new LoginResponse(12L, "acc", "ref", true);
        when(kakaoApiClient.fetchUser("tok")).thenReturn(kakaoUser);
        when(kakaoLoginHandler.login(kakaoUser)).thenReturn(created);

        LoginResponse response = authService.kakaoLogin("tok", true);

        assertThat(response).isEqualTo(created);
        verify(emailProviderConflictGuard).ensureNewAccountAllowed(OauthProvider.KAKAO, "kakao-4", "a@b.com", true, true);
    }
}
