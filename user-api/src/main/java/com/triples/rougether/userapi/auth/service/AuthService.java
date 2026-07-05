package com.triples.rougether.userapi.auth.service;

import com.triples.rougether.userapi.auth.error.AuthErrorCode;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.RefreshToken;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.RefreshTokenRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.shared.CurrencyType;
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
    private final UserWalletRepository userWalletRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenService tokenService;
    private final RefreshTokenReuseGuard refreshTokenReuseGuard;
    private final KakaoApiClient kakaoApiClient;
    private final KakaoLoginHandler kakaoLoginHandler;
    private final GoogleTokenVerifier googleTokenVerifier;
    private final GoogleLoginHandler googleLoginHandler;

    @Transactional
    public LoginResponse devLogin(Long userId) {
        User user;
        boolean isNewUser;
        if (userId == null) {
            user = userRepository.save(User.signUp());
            // 가입 시 통화별 지갑을 함께 발급(COIN=완료 보상, DIAMOND=구매)
            for (CurrencyType currencyType : CurrencyType.values()) {
                userWalletRepository.save(UserWallet.create(user, currencyType));
            }
            isNewUser = true;
        } else {
            user = userRepository.findById(userId)
                    .orElseThrow(() -> new BusinessException(AuthErrorCode.USER_NOT_FOUND));
            isNewUser = false;
        }

        user.recordLogin(Instant.now());
        // 등급 분기 도입 전까지 모든 회원은 NORMAL
        String accessToken = tokenService.issueAccessToken(user.getId(), MemberRole.NORMAL);
        String refreshToken = issueRefreshToken(user);
        return new LoginResponse(user.getId(), accessToken, refreshToken, isNewUser);
    }

    // 카카오 로그인 오케스트레이션. 트랜잭션은 KakaoLoginHandler.login이 소유함(HTTP 호출을 트랜잭션 밖에 둠).
    public LoginResponse kakaoLogin(String accessToken) {
        // 토큰 검증(app_id 대조) 후 카카오 회원번호·email 조회. 실패는 KakaoApiClient가 401/502로 변환함.
        KakaoUser kakaoUser = kakaoApiClient.fetchUser(accessToken);
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
        // idToken 서명·iss·aud·exp 검증 후 sub·email 추출. 실패는 GoogleTokenVerifier가 401/502로 변환함.
        GoogleUser googleUser = googleTokenVerifier.verify(idToken);
        try {
            return googleLoginHandler.login(googleUser);
        } catch (DataIntegrityViolationException race) {
            // 동시 최초가입 경쟁의 패자: 첫 트랜잭션이 통째로 롤백됐으므로 새 트랜잭션(새 스냅샷)으로 재시도.
            return googleLoginHandler.login(googleUser);
        }
    }

    @Transactional
    public TokenResponse refresh(String rawRefreshToken) {
        String hash = tokenService.hashRefreshToken(rawRefreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.REFRESH_TOKEN_INVALID));

        Instant now = Instant.now();
        if (stored.isRevoked()) {
            refreshTokenReuseGuard.revokeAllActive(stored.getUser().getId(), now);
            throw new BusinessException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }
        if (stored.isExpired(now)) {
            throw new BusinessException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }

        // 회전: 원자적 조건부 폐기. 동시 회전에 진 경우(영향행 0) = 이미 폐기됨 → 재사용과 동일 취급.
        int revoked = refreshTokenRepository.revokeIfActive(stored.getId(), now);
        if (revoked == 0) {
            refreshTokenReuseGuard.revokeAllActive(stored.getUser().getId(), now);
            throw new BusinessException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }

        User user = stored.getUser();
        String accessToken = tokenService.issueAccessToken(user.getId(), MemberRole.NORMAL);
        String refreshToken = issueRefreshToken(user);
        return new TokenResponse(accessToken, refreshToken);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        String hash = tokenService.hashRefreshToken(rawRefreshToken);
        // 없어도 조용히 성공(idempotent).
        refreshTokenRepository.findByTokenHash(hash)
                .ifPresent(token -> token.revoke(Instant.now()));
    }

    private String issueRefreshToken(User user) {
        GeneratedRefreshToken generated = tokenService.generateRefreshToken();
        refreshTokenRepository.save(RefreshToken.issue(user, generated.tokenHash(), generated.expiresAt()));
        return generated.raw();
    }
}
