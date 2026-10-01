package com.triples.rougether.userapi.auth.service;

import com.triples.rougether.common.error.AuthErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.RefreshToken;
import com.triples.rougether.domain.member.entity.RefreshTokenRevokeReason;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.RefreshTokenRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.auth.client.KakaoApiClient;
import com.triples.rougether.userapi.auth.config.JwtProperties;
import com.triples.rougether.userapi.auth.dto.TokenResponse;
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
class AuthServiceRefreshTest {

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
    private JwtProperties jwtProperties = new JwtProperties("secret", Duration.ofMinutes(30), Duration.ofDays(14), null);

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository, refreshTokenRepository, tokenService,
                new RefreshTokenRotator(refreshTokenRepository, userRepository, tokenService, jwtProperties), kakaoApiClient, kakaoLoginHandler,
                googleTokenVerifier, googleLoginHandler, appleTokenVerifier, appleLoginHandler,
                appleTokenExchangeClient, appleRefreshTokenCipher, signupService, emailProviderConflictGuard);
    }

    private User userWithId(long id) {
        User user = User.signUp();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private RefreshToken token(User user, long id, String familyId) {
        RefreshToken token = RefreshToken.issueInFamily(user, "hash", Instant.now().plus(Duration.ofDays(1)), familyId);
        ReflectionTestUtils.setField(token, "id", id);
        return token;
    }

    private void presented(RefreshToken token) {
        when(tokenService.hashRefreshToken("raw")).thenReturn("hash");
        when(refreshTokenRepository.findByTokenHashForUpdate("hash")).thenReturn(Optional.of(token));
    }

    private void stubIssue(long userId) {
        when(tokenService.issueAccessToken(eq(userId), any())).thenReturn("new-access");
        when(tokenService.generateRefreshToken())
                .thenReturn(new GeneratedRefreshToken("new-raw", "new-hash", Instant.now().plus(Duration.ofDays(14))));
    }

    private RefreshToken savedToken() {
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        return captor.getValue();
    }

    private void assertRejected() {
        assertThatThrownBy(() -> authService.refresh("raw"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID);
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
        // 거부 경로에서는 마지막 접속 시각을 갱신하지 않음.
        verify(userRepository, never()).updateLastAccessedAt(anyLong(), any(Instant.class));
    }

    @Test
    void 정상_refresh_는_기존_토큰을_ROTATED_로_폐기하고_같은_family_로_새_쌍을_발급한다() {
        User user = userWithId(1L);
        presented(token(user, 100L, "fam-a"));
        when(refreshTokenRepository.revokeIfActive(eq(100L), any(Instant.class),
                eq(RefreshTokenRevokeReason.ROTATED), eq("fam-a"))).thenReturn(1);
        stubIssue(1L);

        TokenResponse response = authService.refresh("raw");

        assertThat(response.accessToken()).isEqualTo("new-access");
        assertThat(response.refreshToken()).isEqualTo("new-raw");
        RefreshToken issued = savedToken();
        assertThat(issued.getFamilyId()).isEqualTo("fam-a");
        assertThat(issued.getTokenHash()).isEqualTo("new-hash");
        verify(userRepository).updateLastAccessedAt(eq(1L), any(Instant.class));
        verify(refreshTokenRepository, never()).revokeActiveInFamily(anyString(), any(), any());
    }

    @Test
    void 레거시_family_없는_토큰을_회전하면_새_family_를_받아_자기_row_와_새_토큰에_기록한다() {
        User user = userWithId(1L);
        presented(token(user, 100L, null));
        ArgumentCaptor<String> family = ArgumentCaptor.forClass(String.class);
        when(refreshTokenRepository.revokeIfActive(eq(100L), any(Instant.class),
                eq(RefreshTokenRevokeReason.ROTATED), family.capture())).thenReturn(1);
        stubIssue(1L);

        authService.refresh("raw");

        assertThat(family.getValue()).isNotBlank();
        assertThat(savedToken().getFamilyId()).isEqualTo(family.getValue());
    }

    @Test
    void 회전된_지_60초_안의_재제출은_응답_유실_재시도로_보고_후속_토큰을_밀어내고_새_쌍을_준다() {
        User user = userWithId(3L);
        RefreshToken rotated = token(user, 300L, "fam-a");
        rotated.revoke(Instant.now().minusSeconds(10), RefreshTokenRevokeReason.ROTATED);
        presented(rotated);
        stubIssue(3L);

        TokenResponse response = authService.refresh("raw");

        assertThat(response.refreshToken()).isEqualTo("new-raw");
        // 받지 못한 후속 토큰만 같은 family 안에서 SUPERSEDED 로 폐기 — 다른 기기(family)·회원 전체는 건드리지 않음.
        verify(refreshTokenRepository).revokeActiveInFamily(eq("fam-a"), any(Instant.class),
                eq(RefreshTokenRevokeReason.SUPERSEDED));
        verify(refreshTokenRepository, never()).revokeActiveInFamily(anyString(), any(),
                eq(RefreshTokenRevokeReason.REUSE));
        verify(refreshTokenRepository, never()).revokeAllActiveByUserId(anyLong(), any(), any());
        verify(refreshTokenRepository, never()).revokeIfActive(anyLong(), any(), any(), any());
        assertThat(savedToken().getFamilyId()).isEqualTo("fam-a");
        verify(userRepository).updateLastAccessedAt(eq(3L), any(Instant.class));
    }

    @Test
    void 유예_재발급으로_밀려난_SUPERSEDED_토큰도_60초_안이면_재발급한다() {
        User user = userWithId(3L);
        RefreshToken superseded = token(user, 301L, "fam-a");
        superseded.revoke(Instant.now().minusSeconds(5), RefreshTokenRevokeReason.SUPERSEDED);
        presented(superseded);
        stubIssue(3L);

        assertThat(authService.refresh("raw").refreshToken()).isEqualTo("new-raw");
        assertThat(savedToken().getFamilyId()).isEqualTo("fam-a");
    }

    @Test
    void 회전된_지_60초가_지난_재제출은_그_family_만_REUSE_로_폐기하고_거부한다() {
        User user = userWithId(9L);
        RefreshToken rotated = token(user, 900L, "fam-a");
        rotated.revoke(Instant.now().minusSeconds(61), RefreshTokenRevokeReason.ROTATED);
        presented(rotated);

        assertRejected();
        verify(refreshTokenRepository).revokeActiveInFamily(eq("fam-a"), any(Instant.class),
                eq(RefreshTokenRevokeReason.REUSE));
        verify(refreshTokenRepository, never()).revokeAllActiveByUserId(anyLong(), any(), any());
    }

    @Test
    void 유예_시간은_설정값을_따른다() {
        jwtProperties = new JwtProperties("secret", Duration.ofMinutes(30), Duration.ofDays(14), Duration.ofSeconds(5));
        setUp();
        User user = userWithId(9L);
        RefreshToken rotated = token(user, 900L, "fam-a");
        rotated.revoke(Instant.now().minusSeconds(10), RefreshTokenRevokeReason.ROTATED);
        presented(rotated);

        assertRejected();
        verify(refreshTokenRepository).revokeActiveInFamily(eq("fam-a"), any(Instant.class),
                eq(RefreshTokenRevokeReason.REUSE));
    }

    @Test
    void 로그아웃으로_폐기된_토큰은_방금이어도_유예_없이_그_family_를_폐기하고_거부한다() {
        User user = userWithId(9L);
        RefreshToken loggedOut = token(user, 900L, "fam-a");
        loggedOut.revoke(Instant.now().minusSeconds(1), RefreshTokenRevokeReason.LOGOUT);
        presented(loggedOut);

        assertRejected();
        verify(refreshTokenRepository).revokeActiveInFamily(eq("fam-a"), any(Instant.class),
                eq(RefreshTokenRevokeReason.REUSE));
        verify(refreshTokenRepository, never()).revokeAllActiveByUserId(anyLong(), any(), any());
    }

    @Test
    void 레거시_family_없는_폐기_토큰_재사용은_회원의_살아있는_토큰을_전부_폐기한다() {
        User user = userWithId(9L);
        RefreshToken legacy = token(user, 900L, null);
        ReflectionTestUtils.setField(legacy, "revokedAt", Instant.now().minusSeconds(1));
        presented(legacy);

        assertRejected();
        verify(refreshTokenRepository).revokeAllActiveByUserId(eq(9L), any(Instant.class),
                eq(RefreshTokenRevokeReason.REUSE));
        verify(refreshTokenRepository, never()).revokeActiveInFamily(anyString(), any(), any());
    }

    @Test
    void 잠금_후_조건부_폐기_영향행0_은_재발급도_다른_기기_폐기도_하지_않고_거부한다() {
        User user = userWithId(5L);
        presented(token(user, 200L, "fam-a"));
        when(refreshTokenRepository.revokeIfActive(eq(200L), any(Instant.class), any(), any())).thenReturn(0);

        assertRejected();
        verify(refreshTokenRepository, never()).revokeActiveInFamily(anyString(), any(), any());
        verify(refreshTokenRepository, never()).revokeAllActiveByUserId(anyLong(), any(), any());
    }

    @Test
    void 탈퇴_회원의_유예_안_재제출은_재발급하지_않는다() {
        User user = userWithId(9L);
        ReflectionTestUtils.setField(user, "deletedAt", Instant.now());
        RefreshToken rotated = token(user, 900L, "fam-a");
        rotated.revoke(Instant.now().minusSeconds(1), RefreshTokenRevokeReason.ROTATED);
        presented(rotated);

        assertRejected();
    }

    @Test
    void 만료된_refresh_는_REFRESH_TOKEN_INVALID_로_거부하고_새_토큰을_만들지_않는다() {
        User user = userWithId(1L);
        RefreshToken expired = RefreshToken.issue(user, "hash", Instant.now().minus(Duration.ofSeconds(1)));
        presented(expired);

        assertRejected();
        verify(refreshTokenRepository, never()).revokeIfActive(anyLong(), any(), any(), any());
    }

    @Test
    void 존재하지_않는_refresh_는_REFRESH_TOKEN_INVALID_로_거부한다() {
        when(tokenService.hashRefreshToken("raw")).thenReturn("hash");
        when(refreshTokenRepository.findByTokenHashForUpdate("hash")).thenReturn(Optional.empty());

        assertRejected();
        verify(refreshTokenRepository, never()).revokeAllActiveByUserId(anyLong(), any(), any());
        verify(refreshTokenRepository, never()).revokeActiveInFamily(anyString(), any(), any());
    }
}
