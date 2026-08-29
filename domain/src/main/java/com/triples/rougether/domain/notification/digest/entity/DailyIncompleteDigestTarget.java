package com.triples.rougether.domain.notification.digest.entity;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "daily_incomplete_digest_targets")
public class DailyIncompleteDigestTarget extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "digest_id", nullable = false)
    private DailyIncompleteDigest digest;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 20, nullable = false)
    private DailyIncompleteDigestTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    private DailyIncompleteDigestTarget(DailyIncompleteDigest digest,
                                        DailyIncompleteDigestTargetType targetType,
                                        Long targetId) {
        this.digest = digest;
        this.targetType = targetType;
        this.targetId = targetId;
    }

    public static DailyIncompleteDigestTarget routine(DailyIncompleteDigest digest, Long lineageId) {
        return new DailyIncompleteDigestTarget(digest, DailyIncompleteDigestTargetType.ROUTINE, lineageId);
    }

    public static DailyIncompleteDigestTarget todo(DailyIncompleteDigest digest, Long todoId) {
        return new DailyIncompleteDigestTarget(digest, DailyIncompleteDigestTargetType.TODO, todoId);
    }
}
