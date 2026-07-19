package com.triples.rougether.userapi.auth.service;

import com.triples.rougether.userapi.auth.error.AuthErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.RefreshToken;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.RefreshTokenRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.auth.client.KakaoApiClient;
import com.triples.rougether.userapi.auth.dto.LoginResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

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

    @Test
    void userId_가_없으면_새_user_를_만들고_isNewUser_true_를_반환한다() {
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 1L);
            return saved;
        });
        stubTokenIssue();

        LoginResponse response = authService.devLogin(null);

        assertThat(response.isNewUser()).isTrue();
        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-raw");
        verify(refreshTokenRepository).save(any(RefreshToken.class));
        // 가입 시 통화별 지갑(COIN·DIAMOND)이 함께 발급돼야 함
        ArgumentCaptor<UserWallet> walletCaptor = ArgumentCaptor.forClass(UserWallet.class);
        verify(userWalletRepository, times(CurrencyType.values().length)).save(walletCaptor.capture());
        assertThat(walletCaptor.getAllValues())
                .extracting(UserWallet::getCurrencyType)
                .containsExactlyInAnyOrder(CurrencyType.values());
    }

    @Test
    void 기존_userId_는_새로_만들지_않고_last_accessed_갱신_후_토큰을_발급한다() {
        User existing = User.signUp();
        ReflectionTestUtils.setField(existing, "id", 7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(existing));
        stubTokenIssue();

        LoginResponse response = authService.devLogin(7L);

        assertThat(response.isNewUser()).isFalse();
        assertThat(response.userId()).isEqualTo(7L);
        assertThat(existing.getLastAccessedAt()).isNotNull();
        verify(userRepository, never()).save(any(User.class));
        verify(userWalletRepository, never()).save(any(UserWallet.class));
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void 존재하지_않는_userId_는_USER_NOT_FOUND_로_거부한다() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.devLogin(99L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.USER_NOT_FOUND);
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    private void stubTokenIssue() {
        when(tokenService.issueAccessToken(any(), any())).thenReturn("access-token");
        when(tokenService.generateRefreshToken())
                .thenReturn(new GeneratedRefreshToken("refresh-raw", "refresh-hash",
                        Instant.now().plus(Duration.ofDays(14))));
    }
}
