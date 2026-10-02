package com.triples.rougether.userapi.auth.service;

import static com.triples.rougether.domain.member.entity.RefreshTokenRevokeReason.REUSE;
import static com.triples.rougether.domain.member.entity.RefreshTokenRevokeReason.ROTATED;
import static com.triples.rougether.domain.member.entity.RefreshTokenRevokeReason.SUPERSEDED;

import com.triples.rougether.domain.member.entity.RefreshToken;
import com.triples.rougether.domain.member.entity.RefreshTokenRevokeReason;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.RefreshTokenRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.auth.config.JwtProperties;
import com.triples.rougether.userapi.auth.dto.TokenResponse;
import com.triples.rougether.userapi.global.security.MemberRole;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// refresh 회전·재사용 판정(mobile #1388).
//
// 거부는 예외가 아니라 Optional.empty() 반환으로 알림 → 재사용 감지 폐기가 거부와 함께 롤백되지 않고 커밋됨.
// (예전 REQUIRES_NEW 분리 대신 이 방식을 씀: 진입 시 토큰 row 를 잠그므로, 별도 트랜잭션이 같은 family 를
//  UPDATE 하면 자기 자신의 잠금을 기다리는 교착이 생김.) 예외 변환은 트랜잭션 밖의 AuthService 가 함.
//
// 동시성: 제시된 토큰 row 를 SELECT ... FOR UPDATE 로 잡고 시작함.
// - 같은 토큰을 든 두 요청은 줄을 섬. 뒤 요청은 앞 요청이 커밋한 최신 상태(ROTATED, 방금)를 보고 유예 규칙을 탐.
//   예전의 "동시 회전 패자(영향행 0) = 재사용 → 전체 폐기"는 이 경로로 흡수됨.
// - 유예 재발급 두 건이 겹쳐도 줄을 서므로, 뒤 건이 앞 건이 만든 토큰을 SUPERSEDED 로 밀어내 family 의 살아있는 토큰은 하나로 남음.
// - family 폐기는 조건부 bulk UPDATE(revoked_at is null) 한 번이라 메모리 잠금 없이 DB 가 원자성을 보장함.
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenRotator {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final JwtProperties jwtProperties;

    @Transactional
    public Optional<TokenResponse> rotate(String tokenHash, Instant now) {
        RefreshToken stored = refreshTokenRepository.findByTokenHashForUpdate(tokenHash).orElse(null);
        if (stored == null) {
            return Optional.empty();
        }
        if (stored.isRevoked()) {
            return handleRevoked(stored, now);
        }
        if (stored.isExpired(now)) {
            return Optional.empty();
        }
        // 탈퇴 회원 잔여 토큰 방어: 탈퇴 트랜잭션의 전량 폐기와 동시에 회전돼 살아남은 토큰도 여기서 거부됨.
        if (stored.getUser().isDeleted()) {
            return Optional.empty();
        }

        // 레거시(V82 이전) 토큰은 회전 시점에 새 family 를 받음 — 자기 row 에도 기록해 이후 유예 판정이 가능해짐.
        String familyId = stored.getFamilyId() != null ? stored.getFamilyId() : RefreshToken.newFamilyId();
        int revoked = refreshTokenRepository.revokeIfActive(stored.getId(), now, ROTATED, familyId);
        if (revoked == 0) {
            // 잠금 조회 뒤라 정상적으로는 오지 않음(잠금 없이 도는 DB 대비 방어). 누가 무슨 사유로 폐기했는지
            // 이 트랜잭션에서는 알 수 없으므로 재발급하지 않고, 다른 기기를 건드리는 폐기도 하지 않음.
            log.warn("refresh 회전 조건부 폐기 영향행 0 — 잠금 후 상태 불일치. userId={}", stored.getUser().getId());
            return Optional.empty();
        }
        return Optional.of(issue(stored.getUser(), familyId, now));
    }

    private Optional<TokenResponse> handleRevoked(RefreshToken stored, Instant now) {
        User user = stored.getUser();
        String familyId = stored.getFamilyId();
        if (isWithinGrace(stored, now) && !stored.isExpired(now) && !user.isDeleted()) {
            // 응답 유실 재시도: 서버는 회전했지만 클라이언트가 새 쌍을 못 받았음.
            // 받지 못한 후속 토큰(들)을 밀어내 family 의 살아있는 토큰을 하나로 유지하고, 같은 family 로 새 쌍을 줌.
            int superseded = refreshTokenRepository.revokeActiveInFamily(familyId, now, SUPERSEDED);
            log.info("refresh 유예 재발급 userId={} superseded={}", user.getId(), superseded);
            return Optional.of(issue(user, familyId, now));
        }

        // 진짜 재사용(유예 밖·회전 외 사유): 그 기기(family)만 폐기함. 레거시(family 없음)는 범위를 알 수 없어 전체 폐기.
        if (familyId == null) {
            int count = refreshTokenRepository.revokeAllActiveByUserId(user.getId(), now, REUSE);
            log.info("refresh 재사용(레거시) 전체 폐기 userId={} revoked={}", user.getId(), count);
        } else {
            int count = refreshTokenRepository.revokeActiveInFamily(familyId, now, REUSE);
            log.info("refresh 재사용 family 폐기 userId={} reason={} revoked={}",
                    user.getId(), stored.getRevokeReason(), count);
        }
        return Optional.empty();
    }

    // 회전 계열 사유로 폐기된 지 유예(기본 60초) 미만이면 응답 유실 재시도로 봄.
    private boolean isWithinGrace(RefreshToken stored, Instant now) {
        RefreshTokenRevokeReason reason = stored.getRevokeReason();
        return stored.getFamilyId() != null
                && reason != null
                && reason.isRotation()
                && stored.getRevokedAt().plus(jwtProperties.refreshReuseGrace()).isAfter(now);
    }

    // 정상 발급 경로에서만 마지막 접속 시각을 갱신함(거부 경로 제외).
    // dirty checking 대신 targeted UPDATE 로 동시 refresh 경합·불필요한 전체 row 갱신을 피함.
    // 주의: bulk UPDATE 는 영속성 컨텍스트를 우회하므로 이후 이 트랜잭션에서 user.getLastAccessedAt() 를 읽으면 옛값이다.
    private TokenResponse issue(User user, String familyId, Instant now) {
        userRepository.updateLastAccessedAt(user.getId(), now);
        String accessToken = tokenService.issueAccessToken(user.getId(), MemberRole.NORMAL);
        GeneratedRefreshToken generated = tokenService.generateRefreshToken();
        refreshTokenRepository.save(
                RefreshToken.issueInFamily(user, generated.tokenHash(), generated.expiresAt(), familyId));
        return new TokenResponse(accessToken, generated.raw());
    }
}
