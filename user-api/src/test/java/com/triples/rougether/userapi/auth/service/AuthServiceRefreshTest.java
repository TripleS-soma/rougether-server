package com.triples.rougether.userapi.auth.service;

import com.triples.rougether.userapi.auth.error.AuthErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.RefreshToken;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.RefreshTokenRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.userapi.auth.client.KakaoApiClient;
import com.triples.rougether.userapi.auth.dto.TokenResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceRefreshTest {

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
    private com.triples.rougether.userapi.auth.client.GoogleTokenVerifier googleTokenVerifier;
    @Mock
    private GoogleLoginHandler googleLoginHandler;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository, userWalletRepository, refreshTokenRepository, tokenService,
                new RefreshTokenReuseGuard(refreshTokenRepository), kakaoApiClient, kakaoLoginHandler,
                googleTokenVerifier, googleLoginHandler);
    }

    private User userWithId(long id) {
        User user = User.signUp();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void 정상_refresh_는_기존_토큰을_원자적으로_폐기하고_새_쌍을_발급한다() {
        User user = userWithId(1L);
        RefreshToken stored = RefreshToken.issue(user, "hash", Instant.now().plus(Duration.ofDays(1)));
        ReflectionTestUtils.setField(stored, "id", 100L);
        when(tokenService.hashRefreshToken("raw")).thenReturn("hash");
        when(refreshTokenRepository.findByTokenHash("hash")).thenReturn(Optional.of(stored));
        when(refreshTokenRepository.revokeIfActive(eq(100L), any(Instant.class))).thenReturn(1);
        when(tokenService.issueAccessToken(eq(1L), any())).thenReturn("new-access");
        when(tokenService.generateRefreshToken())
                .thenReturn(new GeneratedRefreshToken("new-raw", "new-hash", Instant.now().plus(Duration.ofDays(14))));

        TokenResponse response = authService.refresh("raw");

        assertThat(response.accessToken()).isEqualTo("new-access");
        assertThat(response.refreshToken()).isEqualTo("new-raw");
        verify(refreshTokenRepository).revokeIfActive(eq(100L), any(Instant.class));
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void 동시_회전에_진_경우_영향행0_는_재사용으로_보고_user_토큰을_전부_폐기한다() {
        User user = userWithId(5L);
        RefreshToken stored = RefreshToken.issue(user, "hash", Instant.now().plus(Duration.ofDays(1)));
        ReflectionTestUtils.setField(stored, "id", 200L);
        RefreshToken anotherActive = RefreshToken.issue(user, "other-hash", Instant.now().plus(Duration.ofDays(1)));
        when(tokenService.hashRefreshToken("raw")).thenReturn("hash");
        when(refreshTokenRepository.findByTokenHash("hash")).thenReturn(Optional.of(stored));
        when(refreshTokenRepository.revokeIfActive(eq(200L), any(Instant.class))).thenReturn(0);
        when(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(5L)).thenReturn(List.of(anotherActive));

        assertThatThrownBy(() -> authService.refresh("raw"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID);
        assertThat(anotherActive.isRevoked()).isTrue();
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    void 만료된_refresh_는_REFRESH_TOKEN_INVALID_로_거부하고_새_토큰을_만들지_않는다() {
        User user = userWithId(1L);
        RefreshToken expired = RefreshToken.issue(user, "hash", Instant.now().minus(Duration.ofSeconds(1)));
        when(tokenService.hashRefreshToken("raw")).thenReturn("hash");
        when(refreshTokenRepository.findByTokenHash("hash")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.refresh("raw"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID);
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    void 이미_폐기된_refresh_재사용은_user_의_살아있는_토큰을_전부_폐기하고_거부한다() {
        User user = userWithId(9L);
        RefreshToken reused = RefreshToken.issue(user, "hash", Instant.now().plus(Duration.ofDays(1)));
        reused.revoke(Instant.now());
        RefreshToken anotherActive = RefreshToken.issue(user, "other-hash", Instant.now().plus(Duration.ofDays(1)));
        when(tokenService.hashRefreshToken("raw")).thenReturn("hash");
        when(refreshTokenRepository.findByTokenHash("hash")).thenReturn(Optional.of(reused));
        when(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(9L)).thenReturn(List.of(anotherActive));

        assertThatThrownBy(() -> authService.refresh("raw"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID);
        assertThat(anotherActive.isRevoked()).isTrue();
        verify(refreshTokenRepository).findAllByUserIdAndRevokedAtIsNull(9L);
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    void 존재하지_않는_refresh_는_REFRESH_TOKEN_INVALID_로_거부한다() {
        when(tokenService.hashRefreshToken("raw")).thenReturn("hash");
        when(refreshTokenRepository.findByTokenHash("hash")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh("raw"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID);
        verify(refreshTokenRepository, never()).findAllByUserIdAndRevokedAtIsNull(anyLong());
    }
}
