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
import com.triples.rougether.userapi.auth.client.GoogleTokenVerifier;
import com.triples.rougether.userapi.auth.client.GoogleUser;
import com.triples.rougether.userapi.auth.dto.LoginResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.common.error.AuthErrorCode;
import java.util.List;

// 실제 MySQL(Testcontainers)·Flyway에서 구글 최초가입·재로그인의 영속 결과를 검증함. 구글 토큰 검증만 mock.
@SpringBootTest
class GoogleLoginIntegrationTest {

    @MockitoBean
    private GoogleTokenVerifier googleTokenVerifier;

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

    private String uniqueGoogleId() {
        return "google-" + UUID.randomUUID();
    }

    @Test
    void 최초_로그인이면_회원_지갑_연동_이메일을_생성한다() {
        String email = uniqueEmail();
        String googleId = uniqueGoogleId();
        when(googleTokenVerifier.verify("idtok")).thenReturn(new GoogleUser(googleId, email));

        LoginResponse response = authService.googleLogin("idtok");

        assertThat(response.isNewUser()).isTrue();
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();

        User user = userRepository.findById(response.userId()).orElseThrow();
        assertThat(user.getEmail()).isEqualTo(email);
        // 닉네임은 가입 시 비우고 온보딩에서 채움.
        assertThat(user.getNickname()).isNull();

        // 가입 시 지갑 발급 + 초기 잔액(코인 100=온보딩 뽑기 체험용, 다이아 0)
        assertThat(userWalletRepository.findByUserId(user.getId()))
                .extracting(UserWallet::getCurrencyType, UserWallet::getBalance)
                .containsExactlyInAnyOrder(
                        tuple(CurrencyType.COIN, SignupWalletPolicy.INITIAL_COIN_BALANCE),
                        tuple(CurrencyType.DIAMOND, 0));

        assertThat(oauthAccountRepository.findByProviderAndProviderUserId(OauthProvider.GOOGLE, googleId))
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
    void 이미_가입된_구글_회원은_중복_생성_없이_로그인한다() {
        String email = uniqueEmail();
        String googleId = uniqueGoogleId();
        when(googleTokenVerifier.verify("idtok")).thenReturn(new GoogleUser(googleId, email));

        LoginResponse first = authService.googleLogin("idtok");
        LoginResponse second = authService.googleLogin("idtok");

        assertThat(second.isNewUser()).isFalse();
        assertThat(second.userId()).isEqualTo(first.userId());
        // 지갑은 최초 가입 시 통화 수만큼만 발급되고 재로그인으로 늘지 않음.
        assertThat(userWalletRepository.findByUserId(first.userId())).hasSize(CurrencyType.values().length);
    }

    @Test
    void 구글이_이메일을_주지_않으면_email_은_null_로_저장된다() {
        String googleId = uniqueGoogleId();
        when(googleTokenVerifier.verify("idtok")).thenReturn(new GoogleUser(googleId, null));

        LoginResponse response = authService.googleLogin("idtok");

        User user = userRepository.findById(response.userId()).orElseThrow();
        assertThat(user.getEmail()).isNull();
    }

    @Test
    void 재로그인_시_구글_이메일이_바뀌어도_저장된_이메일은_갱신하지_않는다() {
        String firstEmail = uniqueEmail();
        String changedEmail = uniqueEmail();
        String googleId = uniqueGoogleId();
        when(googleTokenVerifier.verify("idtok"))
                .thenReturn(new GoogleUser(googleId, firstEmail))
                .thenReturn(new GoogleUser(googleId, changedEmail));

        LoginResponse first = authService.googleLogin("idtok");
        authService.googleLogin("idtok");

        User user = userRepository.findById(first.userId()).orElseThrow();
        assertThat(user.getEmail()).isEqualTo(firstEmail);
    }

    @Test
    void 같은_provider_회원번호로_연동을_중복_저장하면_DataIntegrityViolationException_이_난다() {
        // AuthService.googleLogin의 동시 최초가입 재시도가 의존하는 전제:
        // uq_oauth_provider_user 충돌이 DataIntegrityViolationException으로 표면화되는지 검증함.
        String googleId = uniqueGoogleId();
        User user = userRepository.save(User.signUp());
        oauthAccountRepository.saveAndFlush(OauthAccount.link(user, OauthProvider.GOOGLE, googleId));

        assertThatThrownBy(() ->
                oauthAccountRepository.saveAndFlush(OauthAccount.link(user, OauthProvider.GOOGLE, googleId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 같은_이메일의_활성_카카오_계정이_있으면_409로_가입을_막는다() {
        String email = "u-" + UUID.randomUUID() + "@example.com";
        User kakaoUser = signupService.register(email);
        oauthAccountRepository.save(OauthAccount.link(kakaoUser, OauthProvider.KAKAO, "kakao-" + UUID.randomUUID()));
        String googleId = uniqueGoogleId();
        when(googleTokenVerifier.verify("idtok")).thenReturn(new GoogleUser(googleId, email, true));

        assertThatThrownBy(() -> authService.googleLogin("idtok"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.EMAIL_LINKED_TO_OTHER_PROVIDER);
                    assertThat(e.getDetails()).containsEntry("providers", List.of("KAKAO"));
                });
        assertThat(oauthAccountRepository.findByProviderAndProviderUserId(OauthProvider.GOOGLE, googleId)).isEmpty();
        // 사용자가 새 계정을 택하면 같은 idToken 으로 가입이 진행됨.
        assertThat(authService.googleLogin("idtok", true).isNewUser()).isTrue();
    }

    // 다른 provider 통합 테스트와 같은 DB 를 공유하므로 이메일이 겹치면 타 provider 계정 안내(409)에 걸린다 — 테스트마다 유일하게.
    private String uniqueEmail() {
        return "u-" + UUID.randomUUID() + "@example.com";
    }
}
