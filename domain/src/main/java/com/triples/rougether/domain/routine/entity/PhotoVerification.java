package com.triples.rougether.domain.routine.entity;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "photo_verifications")
public class PhotoVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "routine_log_id", nullable = false)
    private RoutineLog routineLog;

    @Column(name = "storage_key", length = 255, nullable = false)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "privacy_scope", length = 30, nullable = false)
    private PrivacyScope privacyScope;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_review_status", length = 30, nullable = false)
    private AiReviewStatus aiReviewStatus;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
