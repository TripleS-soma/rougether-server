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
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.common.error.AuthErrorCode;
import java.time.Instant;
import java.util.List;

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
    private SignupService signupService;
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
        String email = uniqueEmail();
        String kakaoId = uniqueKakaoId();
        when(kakaoApiClient.fetchUser("tok")).thenReturn(new KakaoUser(kakaoId, email));

        LoginResponse response = authService.kakaoLogin("tok");

        assertThat(response.isNewUser()).isTrue();
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();

        User user = userRepository.findById(response.userId()).orElseThrow();
        assertThat(user.getEmail()).isEqualTo(email);
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
        String email = uniqueEmail();
        String kakaoId = uniqueKakaoId();
        when(kakaoApiClient.fetchUser("tok")).thenReturn(new KakaoUser(kakaoId, email));

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
        String firstEmail = uniqueEmail();
        String changedEmail = uniqueEmail();
        String kakaoId = uniqueKakaoId();
        when(kakaoApiClient.fetchUser("tok"))
                .thenReturn(new KakaoUser(kakaoId, firstEmail))
                .thenReturn(new KakaoUser(kakaoId, changedEmail));

        LoginResponse first = authService.kakaoLogin("tok");
        authService.kakaoLogin("tok");

        User user = userRepository.findById(first.userId()).orElseThrow();
        assertThat(user.getEmail()).isEqualTo(firstEmail);
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
        String raceEmail = uniqueEmail();
        String kakaoId = uniqueKakaoId();
        when(kakaoApiClient.fetchUser("tok")).thenReturn(new KakaoUser(kakaoId, raceEmail));
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
        assertThat(userRepository.findAll()).filteredOn(u -> raceEmail.equals(u.getEmail())).hasSize(1);
    }

    // --- 같은 이메일의 타 provider 활성 계정 안내(409) ---

    @Test
    void 같은_이메일의_활성_애플_계정이_있으면_409로_가입을_막고_계정을_만들지_않는다() {
        String email = uniqueEmail();
        linkedUser(email, OauthProvider.APPLE);
        String kakaoId = uniqueKakaoId();
        // 대소문자만 다른 이메일도 같은 계정으로 봄.
        when(kakaoApiClient.fetchUser("tok")).thenReturn(new KakaoUser(kakaoId, email.toUpperCase(), true));

        assertThatThrownBy(() -> authService.kakaoLogin("tok"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.EMAIL_LINKED_TO_OTHER_PROVIDER);
                    assertThat(e.getMessage()).isEqualTo("이 이메일은 애플 로그인으로 가입되어 있어요.");
                    assertThat(e.getDetails()).containsEntry("providers", List.of("APPLE"));
                });
        assertThat(oauthAccountRepository.findByProviderAndProviderUserId(OauthProvider.KAKAO, kakaoId)).isEmpty();
    }

    @Test
    void allowNewAccount_면_같은_이메일이어도_새_계정을_만든다() {
        String email = uniqueEmail();
        User appleUser = linkedUser(email, OauthProvider.APPLE);
        String kakaoId = uniqueKakaoId();
        when(kakaoApiClient.fetchUser("tok")).thenReturn(new KakaoUser(kakaoId, email, true));

        LoginResponse response = authService.kakaoLogin("tok", true);

        assertThat(response.isNewUser()).isTrue();
        assertThat(response.userId()).isNotEqualTo(appleUser.getId());
        assertThat(oauthAccountRepository.findByProviderAndProviderUserId(OauthProvider.KAKAO, kakaoId)).isPresent();
    }

    @Test
    void 탈퇴한_계정이나_미인증_이메일은_안내_없이_가입한다() {
        // 탈퇴(soft delete)한 계정의 이메일은 대상이 아님.
        String withdrawnEmail = uniqueEmail();
        User withdrawn = linkedUser(withdrawnEmail, OauthProvider.APPLE);
        withdrawn.softDelete(Instant.now());
        userRepository.save(withdrawn);
        when(kakaoApiClient.fetchUser("tok-1")).thenReturn(new KakaoUser(uniqueKakaoId(), withdrawnEmail, true));
        assertThat(authService.kakaoLogin("tok-1").isNewUser()).isTrue();

        // 카카오가 인증하지 않은 이메일로는 타인 계정의 존재를 캐지 못하게 안내하지 않음.
        String unverifiedEmail = uniqueEmail();
        linkedUser(unverifiedEmail, OauthProvider.GOOGLE);
        when(kakaoApiClient.fetchUser("tok-2")).thenReturn(new KakaoUser(uniqueKakaoId(), unverifiedEmail, false));
        assertThat(authService.kakaoLogin("tok-2").isNewUser()).isTrue();
    }

    @Test
    void 이미_연동된_카카오_회원은_같은_이메일의_타_provider_계정이_있어도_로그인된다() {
        String email = uniqueEmail();
        String kakaoId = uniqueKakaoId();
        when(kakaoApiClient.fetchUser("tok")).thenReturn(new KakaoUser(kakaoId, email, true));
        LoginResponse first = authService.kakaoLogin("tok");
        linkedUser(email, OauthProvider.APPLE);

        LoginResponse second = authService.kakaoLogin("tok");

        assertThat(second.isNewUser()).isFalse();
        assertThat(second.userId()).isEqualTo(first.userId());
    }

    private String uniqueEmail() {
        return "u-" + UUID.randomUUID() + "@example.com";
    }

    // 지정 provider 로 연동된 활성 회원을 만든다(가입 경로와 동일하게 지갑·기본 집 포함).
    private User linkedUser(String email, OauthProvider provider) {
        User user = signupService.register(email);
        oauthAccountRepository.save(OauthAccount.link(user, provider,
                provider.name().toLowerCase() + "-" + UUID.randomUUID()));
        return user;
    }
}
