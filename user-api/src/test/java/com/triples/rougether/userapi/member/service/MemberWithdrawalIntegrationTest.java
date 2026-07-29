package com.triples.rougether.userapi.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.RefreshToken;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.OauthAccountRepository;
import com.triples.rougether.domain.member.repository.RefreshTokenRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.DevicePlatform;
import com.triples.rougether.domain.notification.entity.UserDeviceToken;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.userapi.auth.client.AppleRevokeClient;
import com.triples.rougether.userapi.auth.client.AppleTokenExchangeClient;
import com.triples.rougether.userapi.auth.client.AppleTokenVerifier;
import com.triples.rougether.userapi.auth.client.AppleUser;
import com.triples.rougether.userapi.auth.client.KakaoApiClient;
import com.triples.rougether.userapi.auth.client.KakaoUnlinkClient;
import com.triples.rougether.userapi.auth.client.KakaoUser;
import com.triples.rougether.userapi.auth.dto.LoginResponse;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import com.triples.rougether.userapi.auth.service.AuthService;
import com.triples.rougether.userapi.auth.service.GeneratedRefreshToken;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// 실제 MySQL(Testcontainers)에서 탈퇴 트랜잭션(soft delete + 토큰 전량 폐기 + 연동 삭제)과
// 커밋 후 best-effort provider revoke, 재가입(신규 계정) 흐름을 검증함. 외부 HTTP 클라이언트만 mock.
@SpringBootTest
class MemberWithdrawalIntegrationTest {

    @MockitoBean
    private KakaoApiClient kakaoApiClient;
    @MockitoBean
    private AppleTokenVerifier appleTokenVerifier;
    @MockitoBean
    private AppleTokenExchangeClient appleTokenExchangeClient;
    @MockitoBean
    private KakaoUnlinkClient kakaoUnlinkClient;
    @MockitoBean
    private AppleRevokeClient appleRevokeClient;

    @Autowired
    private MemberWithdrawalService memberWithdrawalService;
    @Autowired
    private AuthService authService;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OauthAccountRepository oauthAccountRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private UserDeviceTokenRepository userDeviceTokenRepository;

    private String kakaoLoginAs(String kakaoId) {
        String token = "tok-" + kakaoId;
        when(kakaoApiClient.fetchUser(token)).thenReturn(new KakaoUser(kakaoId, "a@b.com"));
        return token;
    }

    @Test
    void 탈퇴하면_soft_delete_되고_refresh_전량_폐기_연동_삭제_카카오_unlink_까지_수행된다() {
        String kakaoId = "kakao-" + UUID.randomUUID();
        String token = kakaoLoginAs(kakaoId);
        LoginResponse first = authService.kakaoLogin(token);
        authService.kakaoLogin(token); // 두 기기 로그인 상황: active refresh token 2개
        Long userId = first.userId();
        // 리마인더 push 대상이 되는 FCM 토큰 등록 상황 재현.
        userDeviceTokenRepository.save(UserDeviceToken.register(
                userRepository.findById(userId).orElseThrow(),
                "fcm-" + UUID.randomUUID(), DevicePlatform.ANDROID, java.time.Instant.now()));

        memberWithdrawalService.withdraw(userId);

        User user = userRepository.findById(userId).orElseThrow();
        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(userId)).isEmpty();
        assertThat(oauthAccountRepository.findAllByUser(user)).isEmpty();
        // 루틴은 soft delete 하지 않으므로, push 차단은 FCM 토큰 삭제가 담당함.
        assertThat(userDeviceTokenRepository.findAllByUserId(userId)).isEmpty();
        // 커밋 후 카카오 회원번호로 unlink 호출됨.
        verify(kakaoUnlinkClient).unlink(kakaoId);
    }

    @Test
    void provider_revoke_가_실패해도_탈퇴는_롤백되지_않고_유지된다() {
        String kakaoId = "kakao-" + UUID.randomUUID();
        LoginResponse login = authService.kakaoLogin(kakaoLoginAs(kakaoId));
        doThrow(new BusinessException(AuthErrorCode.OAUTH_KAKAO_UNAVAILABLE))
                .when(kakaoUnlinkClient).unlink(anyString());

        memberWithdrawalService.withdraw(login.userId());

        User user = userRepository.findById(login.userId()).orElseThrow();
        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(login.userId())).isEmpty();
        assertThat(oauthAccountRepository.findAllByUser(user)).isEmpty();
    }

    @Test
    void 애플_연동은_저장된_refresh_token_을_복호화해_revoke_한다() {
        String appleId = "apple-" + UUID.randomUUID();
        when(appleTokenVerifier.verify("idtok")).thenReturn(new AppleUser(appleId, null));
        when(appleTokenExchangeClient.exchangeRefreshToken("authcode")).thenReturn("apple-rt-original");
        LoginResponse login = authService.appleLogin("idtok", "authcode");

        memberWithdrawalService.withdraw(login.userId());

        // 암호화 저장된 토큰이 원문으로 복호화되어 revoke에 사용됨(암복호화 왕복 검증 포함).
        verify(appleRevokeClient).revoke("apple-rt-original");
    }

    @Test
    void 이미_탈퇴한_회원의_재요청은_USER_NOT_FOUND_404_다() {
        LoginResponse login = authService.kakaoLogin(kakaoLoginAs("kakao-" + UUID.randomUUID()));
        memberWithdrawalService.withdraw(login.userId());

        assertThatThrownBy(() -> memberWithdrawalService.withdraw(login.userId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.USER_NOT_FOUND);
    }

    @Test
    void 탈퇴한_소셜_계정으로_재로그인하면_새_계정으로_가입되고_옛_계정은_삭제_상태로_남는다() {
        String kakaoId = "kakao-" + UUID.randomUUID();
        String token = kakaoLoginAs(kakaoId);
        LoginResponse before = authService.kakaoLogin(token);
        memberWithdrawalService.withdraw(before.userId());

        LoginResponse after = authService.kakaoLogin(token);

        // 연동 삭제로 unique 가 풀려 신규 가입 플로우로 새 user 가 생성됨(재가입 허용).
        assertThat(after.isNewUser()).isTrue();
        assertThat(after.userId()).isNotEqualTo(before.userId());
        User oldUser = userRepository.findById(before.userId()).orElseThrow();
        assertThat(oldUser.getDeletedAt()).isNotNull();
        User newUser = userRepository.findById(after.userId()).orElseThrow();
        assertThat(newUser.getDeletedAt()).isNull();
        assertThat(oauthAccountRepository.findAllByUser(newUser)).hasSize(1);
    }

    @Test
    void 탈퇴_직후_살아남은_refresh_token_이_있어도_회전_시점에_거부된다() {
        LoginResponse login = authService.kakaoLogin(kakaoLoginAs("kakao-" + UUID.randomUUID()));
        memberWithdrawalService.withdraw(login.userId());

        // 탈퇴 트랜잭션의 전량 폐기 스냅샷과 동시에 회전돼 살아남은 토큰을 재현함.
        User user = userRepository.findById(login.userId()).orElseThrow();
        GeneratedRefreshToken survived = tokenService.generateRefreshToken();
        refreshTokenRepository.save(
                RefreshToken.issue(user, survived.tokenHash(), survived.expiresAt()));

        assertThatThrownBy(() -> authService.refresh(survived.raw()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.REFRESH_TOKEN_INVALID);
    }
}
