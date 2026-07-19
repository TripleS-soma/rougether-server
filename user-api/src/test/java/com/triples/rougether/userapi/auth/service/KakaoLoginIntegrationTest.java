package com.triples.rougether.userapi.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.member.entity.OauthAccount;
import com.triples.rougether.domain.member.entity.OauthProvider;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
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

        assertThat(userWalletRepository.findByUserId(user.getId()))
                .extracting(UserWallet::getCurrencyType)
                .containsExactlyInAnyOrder(CurrencyType.values());

        assertThat(oauthAccountRepository.findByProviderAndProviderUserId(OauthProvider.KAKAO, kakaoId))
                .isPresent()
                .get()
                .extracting(a -> a.getUser().getId())
                .isEqualTo(user.getId());

        assertThat(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(user.getId())).isNotEmpty();
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
}
