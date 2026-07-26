package com.triples.rougether.userapi.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.member.entity.OauthAccount;
import com.triples.rougether.domain.member.entity.OauthProvider;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.policy.SignupWalletPolicy;
import com.triples.rougether.domain.member.repository.OauthAccountRepository;
import com.triples.rougether.domain.member.repository.RefreshTokenRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.auth.client.AppleTokenVerifier;
import com.triples.rougether.userapi.auth.client.AppleUser;
import com.triples.rougether.userapi.auth.dto.LoginResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// 실제 MySQL(Testcontainers)·Flyway에서 애플 최초가입·재로그인의 영속 결과를 검증함. 애플 토큰 검증만 mock.
@SpringBootTest
class AppleLoginIntegrationTest {

    @MockitoBean
    private AppleTokenVerifier appleTokenVerifier;

    @Autowired
    private AuthService authService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserWalletRepository userWalletRepository;
    @Autowired
    private OauthAccountRepository oauthAccountRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    private String uniqueAppleId() {
        return "apple-" + UUID.randomUUID();
    }

    @Test
    void 최초_로그인이면_회원_지갑_연동_이메일을_생성한다() {
        String appleId = uniqueAppleId();
        when(appleTokenVerifier.verify("idtok")).thenReturn(new AppleUser(appleId, "a@b.com"));

        LoginResponse response = authService.appleLogin("idtok");

        assertThat(response.isNewUser()).isTrue();
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();

        User user = userRepository.findById(response.userId()).orElseThrow();
        assertThat(user.getEmail()).isEqualTo("a@b.com");
        // 닉네임은 가입 시 비우고 온보딩에서 채움.
        assertThat(user.getNickname()).isNull();

        // 가입 시 지갑 발급 + 초기 잔액(코인 750=온보딩 뽑기 체험용, 다이아 0)
        assertThat(userWalletRepository.findByUserId(user.getId()))
                .extracting(UserWallet::getCurrencyType, UserWallet::getBalance)
                .containsExactlyInAnyOrder(
                        tuple(CurrencyType.COIN, SignupWalletPolicy.INITIAL_COIN_BALANCE),
                        tuple(CurrencyType.DIAMOND, 0));

        assertThat(oauthAccountRepository.findByProviderAndProviderUserId(OauthProvider.APPLE, appleId))
                .isPresent()
                .get()
                .extracting(a -> a.getUser().getId())
                .isEqualTo(user.getId());

        assertThat(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(user.getId())).isNotEmpty();
    }

    @Test
    void 이미_가입된_애플_회원은_중복_생성_없이_로그인한다() {
        String appleId = uniqueAppleId();
        when(appleTokenVerifier.verify("idtok")).thenReturn(new AppleUser(appleId, "a@b.com"));

        LoginResponse first = authService.appleLogin("idtok");
        LoginResponse second = authService.appleLogin("idtok");

        assertThat(second.isNewUser()).isFalse();
        assertThat(second.userId()).isEqualTo(first.userId());
        // 지갑은 최초 가입 시 통화 수만큼만 발급되고 재로그인으로 늘지 않음.
        assertThat(userWalletRepository.findByUserId(first.userId())).hasSize(CurrencyType.values().length);
    }

    @Test
    void 애플이_이메일을_주지_않으면_email_은_null_로_저장된다() {
        // 애플은 최초 로그인에만 email을 주므로 email 없이도 가입이 되어야 함.
        String appleId = uniqueAppleId();
        when(appleTokenVerifier.verify("idtok")).thenReturn(new AppleUser(appleId, null));

        LoginResponse response = authService.appleLogin("idtok");

        User user = userRepository.findById(response.userId()).orElseThrow();
        assertThat(user.getEmail()).isNull();
    }

    @Test
    void 이메일_가리기_relay_주소도_그대로_저장한다() {
        String appleId = uniqueAppleId();
        String relay = UUID.randomUUID() + "@privaterelay.appleid.com";
        when(appleTokenVerifier.verify("idtok")).thenReturn(new AppleUser(appleId, relay));

        LoginResponse response = authService.appleLogin("idtok");

        User user = userRepository.findById(response.userId()).orElseThrow();
        assertThat(user.getEmail()).isEqualTo(relay);
    }

    @Test
    void 재로그인_시_애플_이메일이_바뀌어도_저장된_이메일은_갱신하지_않는다() {
        String appleId = uniqueAppleId();
        when(appleTokenVerifier.verify("idtok"))
                .thenReturn(new AppleUser(appleId, "first@b.com"))
                .thenReturn(new AppleUser(appleId, "changed@b.com"));

        LoginResponse first = authService.appleLogin("idtok");
        authService.appleLogin("idtok");

        User user = userRepository.findById(first.userId()).orElseThrow();
        assertThat(user.getEmail()).isEqualTo("first@b.com");
    }

    @Test
    void 동시_최초가입_경쟁에서도_회원은_하나만_생기고_양쪽_로그인에_성공한다() throws Exception {
        // 재시도 경로의 최종 계약: 경쟁 패자는 예외로 새지 않고 승자 회원으로 로그인됨.
        String appleId = uniqueAppleId();
        when(appleTokenVerifier.verify("idtok")).thenReturn(new AppleUser(appleId, "a@b.com"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<LoginResponse>> futures = List.of(
                    pool.submit(() -> {
                        start.await();
                        return authService.appleLogin("idtok");
                    }),
                    pool.submit(() -> {
                        start.await();
                        return authService.appleLogin("idtok");
                    }));
            start.countDown();

            LoginResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
            LoginResponse second = futures.get(1).get(30, TimeUnit.SECONDS);

            // 양쪽 다 성공하고 같은 회원을 가리킴 = 중복 가입이 없음.
            assertThat(first.userId()).isEqualTo(second.userId());
            assertThat(first.accessToken()).isNotBlank();
            assertThat(second.accessToken()).isNotBlank();
            // 한쪽만 신규 가입으로 기록됨.
            assertThat(first.isNewUser() ^ second.isNewUser()).isTrue();
            // 롤백된 트랜잭션의 지갑까지 남지 않았는지 확인.
            assertThat(userWalletRepository.findByUserId(first.userId()))
                    .hasSize(CurrencyType.values().length);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 같은_provider_회원번호로_연동을_중복_저장하면_DataIntegrityViolationException_이_난다() {
        // AuthService.appleLogin의 동시 최초가입 재시도가 의존하는 전제:
        // uq_oauth_provider_user 충돌이 DataIntegrityViolationException으로 표면화되는지 검증함.
        String appleId = uniqueAppleId();
        User user = userRepository.save(User.signUp());
        oauthAccountRepository.saveAndFlush(OauthAccount.link(user, OauthProvider.APPLE, appleId));

        assertThatThrownBy(() ->
                oauthAccountRepository.saveAndFlush(OauthAccount.link(user, OauthProvider.APPLE, appleId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
