package com.triples.rougether.userapi.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.member.entity.OauthAccount;
import com.triples.rougether.domain.member.entity.OauthProvider;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.OauthAccountRepository;
import com.triples.rougether.domain.member.repository.RefreshTokenRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.auth.client.AppleTokenExchangeClient;
import com.triples.rougether.userapi.auth.client.AppleTokenVerifier;
import com.triples.rougether.userapi.auth.client.AppleUser;
import com.triples.rougether.userapi.auth.client.GoogleTokenVerifier;
import com.triples.rougether.userapi.auth.client.GoogleUser;
import com.triples.rougether.userapi.auth.client.KakaoApiClient;
import com.triples.rougether.userapi.auth.client.KakaoUser;
import com.triples.rougether.userapi.auth.dto.LoginResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 테스트 트랜잭션을 사용하지 않고 실제 로그인 프록시가 커밋/롤백한 결과를 새 조회로 확인함.
@SpringBootTest
class SocialLoginTransactionIntegrationTest {

    @MockitoBean private KakaoApiClient kakaoApiClient;
    @MockitoBean private GoogleTokenVerifier googleTokenVerifier;
    @MockitoBean private AppleTokenVerifier appleTokenVerifier;
    @MockitoBean private AppleTokenExchangeClient appleTokenExchangeClient;
    @MockitoSpyBean private TokenService tokenService;

    @Autowired private AuthService authService;
    @Autowired private AppleRefreshTokenCipher appleRefreshTokenCipher;
    @Autowired private UserRepository userRepository;
    @Autowired private OauthAccountRepository oauthAccountRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @ParameterizedTest
    @EnumSource(OauthProvider.class)
    void 토큰_발급이_실패하면_신규_회원의_가입_지급과_연동까지_모두_롤백한다(OauthProvider provider) {
        String providerUserId = UUID.randomUUID().toString();
        String email = UUID.randomUUID() + "@example.com";
        stubIdentity(provider, providerUserId, email);
        Map<String, Long> countsBefore = loginRowCounts();
        IllegalStateException failure = new IllegalStateException("refresh token generation failed");
        // 가입 지급·연동 저장·접속 기록 뒤에 실패시켜 로그인 전체의 원자성을 확인함.
        failRefreshTokenGeneration(failure);

        assertThatThrownBy(() -> login(provider)).isSameAs(failure);

        assertThat(oauthAccountRepository.findByProviderAndProviderUserId(provider, providerUserId))
                .isEmpty();
        assertThat(loginRowCounts()).isEqualTo(countsBefore);
    }

    @Test
    void 애플_재로그인은_같은_계정과_이메일을_유지하고_탈퇴용_토큰만_최신값으로_갱신한다() {
        String appleId = UUID.randomUUID().toString();
        String email = UUID.randomUUID() + "@privaterelay.appleid.com";
        when(appleTokenVerifier.verify("identity-token"))
                .thenAnswer(invocation -> outsideTransaction(new AppleUser(appleId, email)))
                .thenAnswer(invocation -> outsideTransaction(new AppleUser(appleId, null)));
        when(appleTokenExchangeClient.exchangeRefreshToken("authorization-code"))
                .thenAnswer(invocation -> outsideTransaction("first-apple-refresh-token"))
                .thenAnswer(invocation -> outsideTransaction("latest-apple-refresh-token"));
        LoginResponse first = login(OauthProvider.APPLE);
        OauthAccount originalAccount = appleAccount(appleId);
        String originalEncryptedToken = originalAccount.getAppleRefreshTokenEncrypted();
        assertThat(appleRefreshTokenCipher.decrypt(originalEncryptedToken))
                .isEqualTo("first-apple-refresh-token");

        LoginResponse second = login(OauthProvider.APPLE);

        assertThat(second.isNewUser()).isFalse();
        assertThat(second.userId()).isEqualTo(first.userId());
        OauthAccount updatedAccount = appleAccount(appleId);
        assertThat(updatedAccount.getId()).isEqualTo(originalAccount.getId());
        assertThat(updatedAccount.getAppleRefreshTokenEncrypted())
                .isNotEqualTo(originalEncryptedToken)
                .isNotEqualTo("latest-apple-refresh-token");
        assertThat(appleRefreshTokenCipher.decrypt(updatedAccount.getAppleRefreshTokenEncrypted()))
                .isEqualTo("latest-apple-refresh-token");
        assertThat(userRepository.findById(first.userId()).orElseThrow().getEmail()).isEqualTo(email);
        assertThat(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(first.userId()))
                .hasSize(2);
        assertThat(refreshTokenRepository.findByTokenHash(tokenService.hashRefreshToken(second.refreshToken())))
                .isPresent();
    }

    @Test
    void 애플_재로그인의_토큰_발급이_실패하면_접속시각과_탈퇴용_토큰도_기존값으로_롤백한다() {
        String appleId = UUID.randomUUID().toString();
        String email = UUID.randomUUID() + "@example.com";
        stubIdentity(OauthProvider.APPLE, appleId, email);
        LoginResponse first = login(OauthProvider.APPLE);
        OauthAccount originalAccount = appleAccount(appleId);
        String originalEncryptedToken = originalAccount.getAppleRefreshTokenEncrypted();
        User user = userRepository.findById(first.userId()).orElseThrow();
        Instant previousAccess = Instant.parse("2020-01-01T00:00:00Z");
        user.recordAccess(previousAccess);
        userRepository.saveAndFlush(user);
        Map<String, Long> countsBefore = loginRowCounts();
        when(appleTokenVerifier.verify("identity-token")).thenAnswer(invocation -> outsideTransaction(new AppleUser(appleId, null)));
        when(appleTokenExchangeClient.exchangeRefreshToken("authorization-code"))
                .thenAnswer(invocation -> outsideTransaction("replacement-apple-refresh-token"));
        IllegalStateException failure = new IllegalStateException("refresh token generation failed");
        failRefreshTokenGeneration(failure);

        assertThatThrownBy(() -> login(OauthProvider.APPLE)).isSameAs(failure);

        User unchangedUser = userRepository.findById(first.userId()).orElseThrow();
        assertThat(unchangedUser.getLastAccessedAt()).isEqualTo(previousAccess);
        assertThat(unchangedUser.getEmail()).isEqualTo(email);
        OauthAccount unchangedAccount = appleAccount(appleId);
        assertThat(unchangedAccount.getId()).isEqualTo(originalAccount.getId());
        assertThat(unchangedAccount.getAppleRefreshTokenEncrypted()).isEqualTo(originalEncryptedToken);
        assertThat(loginRowCounts()).isEqualTo(countsBefore);
        assertThat(refreshTokenRepository.findByTokenHash(tokenService.hashRefreshToken(first.refreshToken())))
                .isPresent();
    }

    private void stubIdentity(OauthProvider provider, String providerUserId, String email) {
        switch (provider) {
            case KAKAO -> when(kakaoApiClient.fetchUser("identity-token"))
                    .thenAnswer(invocation -> outsideTransaction(new KakaoUser(providerUserId, email)));
            case GOOGLE -> when(googleTokenVerifier.verify("identity-token"))
                    .thenAnswer(invocation -> outsideTransaction(new GoogleUser(providerUserId, email)));
            case APPLE -> {
                when(appleTokenVerifier.verify("identity-token"))
                        .thenAnswer(invocation -> outsideTransaction(new AppleUser(providerUserId, email)));
                when(appleTokenExchangeClient.exchangeRefreshToken("authorization-code"))
                        .thenAnswer(invocation -> outsideTransaction("original-apple-refresh-token"));
            }
        }
    }

    private <T> T outsideTransaction(T response) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("공급자 검증과 코드 교환은 DB 트랜잭션 밖에서 실행됨")
                .isFalse();
        return response;
    }

    private void failRefreshTokenGeneration(RuntimeException failure) {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("로그인 토큰 발급은 가입과 같은 DB 트랜잭션 안에서 실행됨")
                    .isTrue();
            throw failure;
        }).when(tokenService).generateRefreshToken();
    }

    private LoginResponse login(OauthProvider provider) {
        return switch (provider) {
            case KAKAO -> authService.kakaoLogin("identity-token");
            case GOOGLE -> authService.googleLogin("identity-token");
            case APPLE -> authService.appleLogin("identity-token", "authorization-code");
        };
    }

    private OauthAccount appleAccount(String appleId) {
        return oauthAccountRepository.findByProviderAndProviderUserId(OauthProvider.APPLE, appleId)
                .orElseThrow();
    }

    private Map<String, Long> loginRowCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : List.of("users", "oauth_accounts", "user_wallets", "wallet_histories",
                "house", "house_members", "house_goals", "refresh_tokens")) {
            counts.put(table, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        }
        return counts;
    }
}
