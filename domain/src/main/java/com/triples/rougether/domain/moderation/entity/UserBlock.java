package com.triples.rougether.domain.moderation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 한 방향 사용자 차단(#399). 차단한 사람(blocker)의 조회에서만 상대(blocked) 콘텐츠를 뺌.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "user_blocks")
public class UserBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "blocker_user_id", nullable = false, updatable = false)
    private Long blockerUserId;

    @Column(name = "blocked_user_id", nullable = false, updatable = false)
    private Long blockedUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public UserBlock(Long blockerUserId, Long blockedUserId, Instant now) {
        if (blockerUserId.equals(blockedUserId)) {
            throw new IllegalArgumentException("cannot block self: " + blockerUserId);
        }
        this.blockerUserId = blockerUserId;
        this.blockedUserId = blockedUserId;
        this.createdAt = now;
    }
}
