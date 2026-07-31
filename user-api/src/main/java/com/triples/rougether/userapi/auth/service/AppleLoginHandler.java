package com.triples.rougether.userapi.auth.service;

import com.triples.rougether.domain.member.entity.OauthAccount;
import com.triples.rougether.domain.member.entity.OauthProvider;
import com.triples.rougether.domain.member.entity.RefreshToken;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.policy.SignupWalletPolicy;
import com.triples.rougether.domain.member.repository.OauthAccountRepository;
import com.triples.rougether.domain.member.repository.RefreshTokenRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.userapi.auth.client.AppleUser;
import com.triples.rougether.userapi.auth.dto.LoginResponse;
import com.triples.rougether.userapi.global.security.MemberRole;
import com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 애플 회원 find-or-create + 로그인 기록 + 토큰 발급을 한 트랜잭션에서 수행함(KakaoLoginHandler 대칭).
// 생성/조회/발급을 같은 트랜잭션에 두어 방금 만든 회원이 같은 스냅샷에서 보이게 함.
// 동시 최초가입 경쟁의 패자는 uq_oauth_provider_user 충돌로 이 트랜잭션이 통째로 롤백되고,
// 호출측(AuthService.appleLogin)이 새 트랜잭션으로 재시도하면 승자 연동이 보여 로그인으로 전환됨.
@Component
@RequiredArgsConstructor
public class AppleLoginHandler {

    private final UserRepository userRepository;
    private final UserWalletRepository userWalletRepository;
    private final OauthAccountRepository oauthAccountRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenService tokenService;
    private final WalletHistoryRecorder walletHistoryRecorder;

    // encryptedRefreshToken: 탈퇴 시 revoke 호출용으로 저장함. 재로그인 시마다 최신 값으로 갱신됨.
    @Transactional
    public LoginResponse login(AppleUser appleUser, String encryptedRefreshToken) {
        User user;
        boolean isNewUser;
        var existing = oauthAccountRepository
                .findByProviderAndProviderUserId(OauthProvider.APPLE, appleUser.id());
        if (existing.isPresent()) {
            OauthAccount account = existing.get();
            account.updateAppleRefreshToken(encryptedRefreshToken);
            user = account.getUser();
            isNewUser = false;
        } else {
            user = register(appleUser, encryptedRefreshToken);
            isNewUser = true;
        }

        user.recordAccess(Instant.now());
        String accessToken = tokenService.issueAccessToken(user.getId(), MemberRole.NORMAL);
        String refreshToken = issueRefreshToken(user);
        return new LoginResponse(user.getId(), accessToken, refreshToken, isNewUser);
    }

    private User register(AppleUser appleUser, String encryptedRefreshToken) {
        // 애플은 최초 로그인에만 email을 주므로 가입 시점 값만 저장하고 재로그인으로 갱신하지 않음.
        User user = userRepository.save(User.signUp(appleUser.email()));
        // 가입 시 통화별 지갑을 함께 발급(COIN=완료 보상, DIAMOND=구매). 초기 잔액은 SignupWalletPolicy 소관.
        // 가입 보너스는 재화 원장에도 기록함(#253).
        walletHistoryRecorder.recordSignupBonus(
                userWalletRepository.saveAll(SignupWalletPolicy.issueAll(user)));
        OauthAccount account = OauthAccount.link(user, OauthProvider.APPLE, appleUser.id());
        account.updateAppleRefreshToken(encryptedRefreshToken);
        // IDENTITY 전략이라 즉시 INSERT됨 → 경쟁 패자는 여기서 unique 충돌이 발생함.
        oauthAccountRepository.save(account);
        return user;
    }

    private String issueRefreshToken(User user) {
        GeneratedRefreshToken generated = tokenService.generateRefreshToken();
        refreshTokenRepository.save(RefreshToken.issue(user, generated.tokenHash(), generated.expiresAt()));
        return generated.raw();
    }
}
