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
import com.triples.rougether.userapi.auth.client.KakaoApiClient;
import com.triples.rougether.userapi.auth.client.KakaoUser;
import com.triples.rougether.userapi.auth.dto.LoginResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// 실제 MySQL(Testcontainers)·Flyway(V5)에서 카카오 최초가입·재로그인의 영속 결과를 검증함. 카카오 API만 mock.
@SpringBootTest
class KakaoLoginIntegrationTest {

    @MockitoBean
    private KakaoApiClient kakaoApiClient;

    @Autowired
    private com.triples.rougether.domain.house.repository.HouseMemberRepository houseMemberRepository;
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

    private String uniqueKakaoId() {
        return "kakao-" + UUID.randomUUID();
    }

    @Test
    void 최초_로그인이면_회원_지갑_연동_이메일을_생성한다() {
        String kakaoId = uniqueKakaoId();
        when(kakaoApiClient.fetchUser("tok")).thenReturn(new KakaoUser(kakaoId, "a@b.com"));

        LoginResponse response = authService.kakaoLogin("tok");

        assertThat(response.isNewUser()).isTrue();
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();

        User user = userRepository.findById(response.userId()).orElseThrow();
        assertThat(user.getEmail()).isEqualTo("a@b.com");
        // 닉네임은 가입 시 비우고 온보딩에서 채움.
        assertThat(user.getNickname()).isNull();
        // 로그인 성공 시 마지막 접속 시각 기록(회귀 확인).
        assertThat(user.getLastAccessedAt()).isNotNull();

        // 가입 시 지갑 발급 + 초기 잔액(코인 100=온보딩 뽑기 체험용, 다이아 0)
        assertThat(userWalletRepository.findByUserId(user.getId()))
                .extracting(UserWallet::getCurrencyType, UserWallet::getBalance)
                .containsExactlyInAnyOrder(
                        tuple(CurrencyType.COIN, SignupWalletPolicy.INITIAL_COIN_BALANCE),
                        tuple(CurrencyType.DIAMOND, 0));

        assertThat(oauthAccountRepository.findByProviderAndProviderUserId(OauthProvider.KAKAO, kakaoId))
                .isPresent()
                .get()
                .extracting(a -> a.getUser().getId())
                .isEqualTo(user.getId());

        assertThat(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(user.getId())).isNotEmpty();

        // 가입과 함께 기본 집(나의 집) 지급(#322) — 온보딩 전에도 내 집이 하나 있다
        assertThat(houseMemberRepository.findByUserIdAndStatusWithHouse(user.getId(),
                com.triples.rougether.domain.house.entity.HouseMemberStatus.ACTIVE))
                .singleElement()
                .satisfies(m -> assertThat(m.getHouse().getName()).isEqualTo("나의 집"));
    }

    @Test
    void 이미_가입된_카카오_회원은_중복_생성_없이_로그인한다() {
        String kakaoId = uniqueKakaoId();
        when(kakaoApiClient.fetchUser("tok")).thenReturn(new KakaoUser(kakaoId, "a@b.com"));

        LoginResponse first = authService.kakaoLogin("tok");
        LoginResponse second = authService.kakaoLogin("tok");

        assertThat(second.isNewUser()).isFalse();
        assertThat(second.userId()).isEqualTo(first.userId());
        // 지갑은 최초 가입 시 통화 수만큼만 발급되고 재로그인으로 늘지 않음.
        assertThat(userWalletRepository.findByUserId(first.userId())).hasSize(CurrencyType.values().length);
    }

    @Test
    void 카카오가_이메일을_주지_않으면_email_은_null_로_저장된다() {
        String kakaoId = uniqueKakaoId();
        when(kakaoApiClient.fetchUser("tok")).thenReturn(new KakaoUser(kakaoId, null));

        LoginResponse response = authService.kakaoLogin("tok");

        User user = userRepository.findById(response.userId()).orElseThrow();
        assertThat(user.getEmail()).isNull();
    }

    @Test
    void 재로그인_시_카카오_이메일이_바뀌어도_저장된_이메일은_갱신하지_않는다() {
        String kakaoId = uniqueKakaoId();
        when(kakaoApiClient.fetchUser("tok"))
                .thenReturn(new KakaoUser(kakaoId, "first@b.com"))
                .thenReturn(new KakaoUser(kakaoId, "changed@b.com"));

        LoginResponse first = authService.kakaoLogin("tok");
        authService.kakaoLogin("tok");

        User user = userRepository.findById(first.userId()).orElseThrow();
        assertThat(user.getEmail()).isEqualTo("first@b.com");
    }

    @Test
    void 같은_provider_회원번호로_연동을_중복_저장하면_DataIntegrityViolationException_이_난다() {
        // AuthService.kakaoLogin의 동시 최초가입 재시도가 의존하는 전제:
        // uq_oauth_provider_user 충돌이 DataIntegrityViolationException으로 표면화되는지 검증함.
        String kakaoId = uniqueKakaoId();
        User user = userRepository.save(User.signUp());
        oauthAccountRepository.saveAndFlush(OauthAccount.link(user, OauthProvider.KAKAO, kakaoId));

        assertThatThrownBy(() ->
                oauthAccountRepository.saveAndFlush(OauthAccount.link(user, OauthProvider.KAKAO, kakaoId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // #322: 동시 최초가입 경쟁에서 패자 트랜잭션이 통째로 롤백돼 고아 유저·고아 기본 집이 남지 않는지(최종 상태로 검증)
    @Test
    void 동시_최초_로그인_경쟁이_나도_회원과_기본_집은_하나만_남는다() throws Exception {
        String kakaoId = uniqueKakaoId();
        when(kakaoApiClient.fetchUser("tok")).thenReturn(new KakaoUser(kakaoId, "race@b.com"));
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.List<java.util.concurrent.Future<LoginResponse>> results = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return authService.kakaoLogin("tok");
            }));
        }
        start.countDown();
        java.util.Set<Long> userIds = new java.util.HashSet<>();
        for (java.util.concurrent.Future<LoginResponse> f : results) {
            userIds.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS).userId());
        }
        pool.shutdownNow();

        // 두 호출 모두 같은 회원으로 수렴
        assertThat(userIds).hasSize(1);
        Long userId = userIds.iterator().next();
        assertThat(oauthAccountRepository.findByProviderAndProviderUserId(OauthProvider.KAKAO, kakaoId))
                .isPresent().get().extracting(a -> a.getUser().getId()).isEqualTo(userId);
        // 기본 집은 승자 것 하나뿐(패자의 집·유저는 롤백)
        assertThat(houseMemberRepository.findByUserIdAndStatusWithHouse(userId,
                com.triples.rougether.domain.house.entity.HouseMemberStatus.ACTIVE)).hasSize(1);
        assertThat(userRepository.findAll()).filteredOn(u -> "race@b.com".equals(u.getEmail())).hasSize(1);
    }
}
