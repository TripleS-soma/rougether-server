package com.triples.rougether.domain.member.entity;

import com.triples.rougether.domain.support.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// refresh 토큰 회전(RTR) 상태. 원문이 아니라 해시(token_hash)만 저장함.
// family_id 는 로그인 1회(기기 1대)의 회전 사슬 식별자. 재사용 감지 시 폐기 범위가 됨(null = V82 이전 레거시).
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", length = 255, nullable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "family_id", length = 36)
    private String familyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason", length = 20)
    private RefreshTokenRevokeReason revokeReason;

    private RefreshToken(User user, String tokenHash, Instant expiresAt, String familyId) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.familyId = familyId;
    }

    // 로그인 발급: 새 family 를 시작함.
    public static RefreshToken issue(User user, String tokenHash, Instant expiresAt) {
        return new RefreshToken(user, tokenHash, expiresAt, newFamilyId());
    }

    // 회전 발급: 이전 토큰의 family 를 물려받음.
    public static RefreshToken issueInFamily(User user, String tokenHash, Instant expiresAt, String familyId) {
        return new RefreshToken(user, tokenHash, expiresAt, familyId);
    }

    public static String newFamilyId() {
        return UUID.randomUUID().toString();
    }

    // 이미 폐기된 토큰은 최초 사유·시각을 유지함(유예 판정 기준이 바뀌지 않게).
    public void revoke(Instant now, RefreshTokenRevokeReason reason) {
        if (revokedAt == null) {
            this.revokedAt = now;
            this.revokeReason = reason;
        }
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    // 회전·재사용 검증용. 살아있고(미폐기) 만료 전이어야 유효함.
    public boolean isActive(Instant now) {
        return !isRevoked() && !isExpired(now);
    }
}
