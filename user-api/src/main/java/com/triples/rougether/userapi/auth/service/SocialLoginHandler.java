package com.triples.rougether.userapi.auth.service;

import com.triples.rougether.domain.member.entity.OauthAccount;
import com.triples.rougether.domain.member.entity.OauthProvider;
import com.triples.rougether.domain.member.entity.RefreshToken;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.OauthAccountRepository;
import com.triples.rougether.domain.member.repository.RefreshTokenRepository;
import com.triples.rougether.userapi.auth.client.AppleUser;
import com.triples.rougether.userapi.auth.client.GoogleUser;
import com.triples.rougether.userapi.auth.client.KakaoUser;
import com.triples.rougether.userapi.auth.dto.LoginResponse;
import com.triples.rougether.userapi.global.security.MemberRole;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 검증된 소셜 계정의 회원 조회·가입·연동·접속 기록·토큰 저장을 한 트랜잭션에서 수행함
// 동시 최초 가입의 unique 충돌은 전체 롤백 후 AuthService가 프록시를 다시 호출해 재시도함
@Component
@RequiredArgsConstructor
@Transactional
public class SocialLoginHandler {

    private final OauthAccountRepository oauthAccountRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenService tokenService;
    private final SignupService signupService;

    public LoginResponse login(KakaoUser kakaoUser) {
        return login(OauthProvider.KAKAO, kakaoUser.id(), kakaoUser.email(), null);
    }

    public LoginResponse login(GoogleUser googleUser) {
        return login(OauthProvider.GOOGLE, googleUser.id(), googleUser.email(), null);
    }

    public LoginResponse login(AppleUser appleUser, String encryptedRefreshToken) {
        return login(OauthProvider.APPLE, appleUser.id(), appleUser.email(), encryptedRefreshToken);
    }

    private LoginResponse login(OauthProvider provider, String providerUserId, String email,
                                String encryptedAppleRefreshToken) {
        var existing = oauthAccountRepository.findByProviderAndProviderUserId(provider, providerUserId);
        boolean isNewUser = existing.isEmpty();
        OauthAccount account;
        if (isNewUser) {
            // 이메일은 최초 가입에만 저장함. 기존 회원의 이메일은 갱신하지 않음
            User user = signupService.register(email);
            account = OauthAccount.link(user, provider, providerUserId);
        } else {
            account = existing.get();
        }

        if (provider == OauthProvider.APPLE) {
            // 탈퇴 시 revoke용 암호문을 신규·재로그인 모두 갱신함
            account.updateAppleRefreshToken(encryptedAppleRefreshToken);
        }
        if (isNewUser) {
            // 애플 암호문까지 채운 뒤 INSERT함. IDENTITY라 경쟁 패자의 unique 충돌이 여기서 발생함
            oauthAccountRepository.save(account);
        }

        User user = account.getUser();
        user.recordAccess(Instant.now());
        String accessToken = tokenService.issueAccessToken(user.getId(), MemberRole.NORMAL);
        String refreshToken = issueRefreshToken(user);
        return new LoginResponse(user.getId(), accessToken, refreshToken, isNewUser);
    }

    private String issueRefreshToken(User user) {
        GeneratedRefreshToken generated = tokenService.generateRefreshToken();
        refreshTokenRepository.save(RefreshToken.issue(user, generated.tokenHash(), generated.expiresAt()));
        return generated.raw();
    }
}
