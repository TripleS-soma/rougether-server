package com.triples.rougether.userapi.auth.service;

import com.triples.rougether.domain.member.entity.OauthAccount;
import com.triples.rougether.domain.member.entity.OauthProvider;
import com.triples.rougether.domain.member.entity.RefreshToken;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.OauthAccountRepository;
import com.triples.rougether.domain.member.repository.RefreshTokenRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.auth.client.KakaoUser;
import com.triples.rougether.userapi.auth.dto.LoginResponse;
import com.triples.rougether.userapi.global.security.MemberRole;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 카카오 회원 find-or-create + 로그인 기록 + 토큰 발급을 한 트랜잭션에서 수행함.
// 생성/조회/발급을 같은 트랜잭션에 두어 방금 만든 회원이 같은 스냅샷에서 보이게 함(REQUIRES_NEW 가시성 문제 회피).
// 동시 최초가입 경쟁의 패자는 uq_oauth_provider_user 충돌로 이 트랜잭션이 통째로 롤백되고,
// 호출측(AuthService.kakaoLogin)이 새 트랜잭션으로 재시도하면 승자 연동이 보여 로그인으로 전환됨.
@Component
@RequiredArgsConstructor
public class KakaoLoginHandler {

    private final UserRepository userRepository;
    private final UserWalletRepository userWalletRepository;
    private final OauthAccountRepository oauthAccountRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenService tokenService;

    @Transactional
    public LoginResponse login(KakaoUser kakaoUser) {
        User user;
        boolean isNewUser;
        var existing = oauthAccountRepository
                .findByProviderAndProviderUserId(OauthProvider.KAKAO, kakaoUser.id());
        if (existing.isPresent()) {
            user = existing.get().getUser();
            isNewUser = false;
        } else {
            user = register(kakaoUser);
            isNewUser = true;
        }

        user.recordLogin(Instant.now());
        String accessToken = tokenService.issueAccessToken(user.getId(), MemberRole.NORMAL);
        String refreshToken = issueRefreshToken(user);
        return new LoginResponse(user.getId(), accessToken, refreshToken, isNewUser);
    }

    private User register(KakaoUser kakaoUser) {
        User user = userRepository.save(User.signUp(kakaoUser.email()));
        // 가입 시 통화별 지갑을 함께 발급(COIN=완료 보상, DIAMOND=구매)
        for (CurrencyType currencyType : CurrencyType.values()) {
            userWalletRepository.save(UserWallet.create(user, currencyType));
        }
        // IDENTITY 전략이라 즉시 INSERT됨 → 경쟁 패자는 여기서 unique 충돌이 발생함.
        oauthAccountRepository.save(OauthAccount.link(user, OauthProvider.KAKAO, kakaoUser.id()));
        return user;
    }

    private String issueRefreshToken(User user) {
        GeneratedRefreshToken generated = tokenService.generateRefreshToken();
        refreshTokenRepository.save(RefreshToken.issue(user, generated.tokenHash(), generated.expiresAt()));
        return generated.raw();
    }
}
