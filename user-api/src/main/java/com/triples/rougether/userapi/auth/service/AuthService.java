package com.triples.rougether.userapi.auth.service;

import com.triples.rougether.common.error.AuthErrorCode;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.OauthProvider;
import com.triples.rougether.domain.member.entity.RefreshToken;
import com.triples.rougether.domain.member.entity.RefreshTokenRevokeReason;
import com.triples.rougether.domain.member.entity.User;
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
import com.triples.rougether.userapi.auth.dto.TokenResponse;
import com.triples.rougether.userapi.global.security.MemberRole;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenService tokenService;
    private final RefreshTokenRotator refreshTokenRotator;
    private final KakaoApiClient kakaoApiClient;
    private final KakaoLoginHandler kakaoLoginHandler;
    private final GoogleTokenVerifier googleTokenVerifier;
    private final GoogleLoginHandler googleLoginHandler;
    private final AppleTokenVerifier appleTokenVerifier;
    private final AppleLoginHandler appleLoginHandler;
    private final AppleTokenExchangeClient appleTokenExchangeClient;
    private final AppleRefreshTokenCipher appleRefreshTokenCipher;
    private final SignupService signupService;
    private final EmailProviderConflictGuard emailProviderConflictGuard;

    @Transactional
    public LoginResponse devLogin(Long userId) {
        User user;
        boolean isNewUser;
        if (userId == null) {
            // 신규 가입 — 지갑·원장·기본 집까지 소셜 가입과 동일하게 지급(SignupService)
            user = signupService.register(null);
            isNewUser = true;
        } else {
            // 탈퇴(soft delete) 회원은 없는 회원과 동일하게 거부함.
            user = userRepository.findByIdAndDeletedAtIsNull(userId)
                    .orElseThrow(() -> new BusinessException(AuthErrorCode.USER_NOT_FOUND));
            // 동거 봇(#307)은 서버가 움직이는 로그인 불가 계정 — dev-login 으로도 토큰을 발급하지 않는다.
            if (user.isBot()) {
                throw new BusinessException(AuthErrorCode.BOT_LOGIN_NOT_ALLOWED);
            }
            isNewUser = false;
        }

        user.recordAccess(Instant.now());
        // 등급 분기 도입 전까지 모든 회원은 NORMAL
        String accessToken = tokenService.issueAccessToken(user.getId(), MemberRole.NORMAL);
        String refreshToken = issueRefreshToken(user);
        return new LoginResponse(user.getId(), accessToken, refreshToken, isNewUser);
    }

    // 카카오 로그인 오케스트레이션. 트랜잭션은 KakaoLoginHandler.login이 소유함(HTTP 호출을 트랜잭션 밖에 둠).
    public LoginResponse kakaoLogin(String accessToken) {
        return kakaoLogin(accessToken, false);
    }

    // allowNewAccount: 같은 이메일의 타 provider 활성 계정이 있어도 새 계정 생성을 허용함(409 안내 뒤 사용자가 고른 재요청).
    public LoginResponse kakaoLogin(String accessToken, boolean allowNewAccount) {
        // 토큰 검증(app_id 대조) 후 카카오 회원번호·email 조회. 실패는 KakaoApiClient가 401/502로 변환함.
        KakaoUser kakaoUser = kakaoApiClient.fetchUser(accessToken);
        // 최초 가입이면 같은 이메일의 타 provider 계정 안내(409) — 가입 트랜잭션 앞에서 막아 빈 계정을 만들지 않음.
        emailProviderConflictGuard.ensureNewAccountAllowed(OauthProvider.KAKAO, kakaoUser.id(),
                kakaoUser.email(), kakaoUser.emailVerified(), allowNewAccount);
        try {
            return kakaoLoginHandler.login(kakaoUser);
        } catch (DataIntegrityViolationException race) {
            // 동시 최초가입 경쟁의 패자: 첫 트랜잭션이 통째로 롤백됐으므로 새 트랜잭션(새 스냅샷)으로 재시도.
            // 이제 승자가 만든 연동이 보여 로그인으로 전환됨.
            return kakaoLoginHandler.login(kakaoUser);
        }
    }

    // 구글 로그인 오케스트레이션. 트랜잭션은 GoogleLoginHandler.login이 소유함(JWK 검증을 트랜잭션 밖에 둠).
    public LoginResponse googleLogin(String idToken) {
        return googleLogin(idToken, false);
    }

    public LoginResponse googleLogin(String idToken, boolean allowNewAccount) {
        // idToken 서명·iss·aud·exp 검증 후 sub·email 추출. 실패는 GoogleTokenVerifier가 401/502로 변환함.
        GoogleUser googleUser = googleTokenVerifier.verify(idToken);
        emailProviderConflictGuard.ensureNewAccountAllowed(OauthProvider.GOOGLE, googleUser.id(),
                googleUser.email(), googleUser.emailVerified(), allowNewAccount);
        try {
            return googleLoginHandler.login(googleUser);
        } catch (DataIntegrityViolationException race) {
            // 동시 최초가입 경쟁의 패자: 첫 트랜잭션이 통째로 롤백됐으므로 새 트랜잭션(새 스냅샷)으로 재시도.
            return googleLoginHandler.login(googleUser);
        }
    }

    // 애플 로그인 오케스트레이션. 트랜잭션은 AppleLoginHandler.login이 소유함(JWK 검증·코드 교환 HTTP를 트랜잭션 밖에 둠).
    public LoginResponse appleLogin(String idToken, String authorizationCode) {
        return appleLogin(idToken, authorizationCode, false);
    }

    public LoginResponse appleLogin(String idToken, String authorizationCode, boolean allowNewAccount) {
        // identityToken 서명·iss·aud·exp 검증 후 sub·email 추출. 실패는 AppleTokenVerifier가 401/502로 변환함.
        AppleUser appleUser = appleTokenVerifier.verify(idToken);
        // authorizationCode 는 1회용이라 교환 **앞**에서 검사함 — 409 뒤 같은 코드로 allowNewAccount 재요청이 가능해야 함.
        emailProviderConflictGuard.ensureNewAccountAllowed(OauthProvider.APPLE, appleUser.id(),
                appleUser.email(), appleUser.emailVerified(), allowNewAccount);
        // 탈퇴 시 revoke 호출용 refresh token을 교환·암호화해 연동에 저장함. 교환 실패는 로그인 실패(401/502).
        String encryptedRefreshToken = appleRefreshTokenCipher.encrypt(
                appleTokenExchangeClient.exchangeRefreshToken(authorizationCode));
        try {
            return appleLoginHandler.login(appleUser, encryptedRefreshToken);
        } catch (DataIntegrityViolationException race) {
            // 동시 최초가입 경쟁의 패자: 첫 트랜잭션이 통째로 롤백됐으므로 새 트랜잭션(새 스냅샷)으로 재시도.
            return appleLoginHandler.login(appleUser, encryptedRefreshToken);
        }
    }

    // 트랜잭션은 RefreshTokenRotator 가 소유함. 거부(재사용 감지 폐기 포함)가 커밋된 뒤에 여기서 401 로 변환함.
    public TokenResponse refresh(String rawRefreshToken) {
        String hash = tokenService.hashRefreshToken(rawRefreshToken);
        return refreshTokenRotator.rotate(hash, Instant.now())
                .orElseThrow(() -> new BusinessException(AuthErrorCode.REFRESH_TOKEN_INVALID));
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        String hash = tokenService.hashRefreshToken(rawRefreshToken);
        // 없어도 조용히 성공(idempotent).
        refreshTokenRepository.findByTokenHash(hash)
                .ifPresent(token -> token.revoke(Instant.now(), RefreshTokenRevokeReason.LOGOUT));
    }

    private String issueRefreshToken(User user) {
        GeneratedRefreshToken generated = tokenService.generateRefreshToken();
        refreshTokenRepository.save(RefreshToken.issue(user, generated.tokenHash(), generated.expiresAt()));
        return generated.raw();
    }
}
