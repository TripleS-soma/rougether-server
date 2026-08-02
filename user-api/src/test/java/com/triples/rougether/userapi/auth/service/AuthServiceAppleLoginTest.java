package com.triples.rougether.userapi.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.member.repository.RefreshTokenRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.userapi.auth.client.AppleTokenVerifier;
import com.triples.rougether.userapi.auth.client.AppleUser;
import com.triples.rougether.userapi.auth.client.GoogleTokenVerifier;
import com.triples.rougether.userapi.auth.client.KakaoApiClient;
import com.triples.rougether.userapi.auth.dto.LoginResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

// appleLogin 오케스트레이션(identityToken 검증 → 핸들러 위임 → 경쟁 충돌 시 재시도)만 검증함.
// find-or-create·영속 결과는 AppleLoginIntegrationTest가 실제 DB로 검증함.
@ExtendWith(MockitoExtension.class)
class AuthServiceAppleLoginTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserWalletRepository userWalletRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private TokenService tokenService;
    @Mock
    private KakaoApiClient kakaoApiClient;
    @Mock
    private KakaoLoginHandler kakaoLoginHandler;
    @Mock
    private GoogleTokenVerifier googleTokenVerifier;
    @Mock
    private GoogleLoginHandler googleLoginHandler;
    @Mock
    private AppleTokenVerifier appleTokenVerifier;
    @Mock
    private AppleLoginHandler appleLoginHandler;
    @Mock
    private com.triples.rougether.userapi.auth.client.AppleTokenExchangeClient appleTokenExchangeClient;
    @Mock
    private AppleRefreshTokenCipher appleRefreshTokenCipher;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository, userWalletRepository, refreshTokenRepository, tokenService,
                new RefreshTokenReuseGuard(refreshTokenRepository), kakaoApiClient, kakaoLoginHandler,
                googleTokenVerifier, googleLoginHandler, appleTokenVerifier, appleLoginHandler,
                appleTokenExchangeClient, appleRefreshTokenCipher,
                org.mockito.Mockito.mock(com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder.class));
    }

    @Test
    void 애플_검증_결과로_핸들러에_위임하고_응답을_그대로_반환한다() {
        AppleUser appleUser = new AppleUser("apple-1", "a@b.com");
        LoginResponse expected = new LoginResponse(10L, "acc", "ref", true);
        when(appleTokenVerifier.verify("idtok")).thenReturn(appleUser);
        when(appleTokenExchangeClient.exchangeRefreshToken("authcode")).thenReturn("apple-rt");
        when(appleRefreshTokenCipher.encrypt("apple-rt")).thenReturn("enc-rt");
        when(appleLoginHandler.login(appleUser, "enc-rt")).thenReturn(expected);

        LoginResponse response = authService.appleLogin("idtok", "authcode");

        assertThat(response).isEqualTo(expected);
        verify(appleLoginHandler, times(1)).login(appleUser, "enc-rt");
    }

    @Test
    void 기존_회원이면_isNewUser_는_false_다() {
        AppleUser appleUser = new AppleUser("apple-2", null);
        LoginResponse expected = new LoginResponse(11L, "acc", "ref", false);
        when(appleTokenVerifier.verify("idtok")).thenReturn(appleUser);
        when(appleTokenExchangeClient.exchangeRefreshToken("authcode")).thenReturn("apple-rt");
        when(appleRefreshTokenCipher.encrypt("apple-rt")).thenReturn("enc-rt");
        when(appleLoginHandler.login(appleUser, "enc-rt")).thenReturn(expected);

        LoginResponse response = authService.appleLogin("idtok", "authcode");

        assertThat(response.isNewUser()).isFalse();
        assertThat(response.userId()).isEqualTo(11L);
    }

    @Test
    void 동시_최초가입_경쟁에서_unique_충돌이_나면_새_트랜잭션으로_재시도해_로그인으로_전환한다() {
        AppleUser appleUser = new AppleUser("apple-3", "a@b.com");
        LoginResponse afterRetry = new LoginResponse(9L, "acc", "ref", false);
        when(appleTokenVerifier.verify("idtok")).thenReturn(appleUser);
        when(appleTokenExchangeClient.exchangeRefreshToken("authcode")).thenReturn("apple-rt");
        when(appleRefreshTokenCipher.encrypt("apple-rt")).thenReturn("enc-rt");
        // 첫 시도는 경쟁 충돌로 롤백, 재시도는 승자 연동으로 로그인 성공.
        when(appleLoginHandler.login(appleUser, "enc-rt"))
                .thenThrow(new DataIntegrityViolationException("uq_oauth_provider_user"))
                .thenReturn(afterRetry);

        LoginResponse response = authService.appleLogin("idtok", "authcode");

        assertThat(response.isNewUser()).isFalse();
        assertThat(response.userId()).isEqualTo(9L);
        verify(appleLoginHandler, times(2)).login(appleUser, "enc-rt");
    }
}
