package com.triples.rougether.domain.recommendation.entity;

import com.triples.rougether.domain.member.entity.User;
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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 사용자·실험 키별 영구 variant 배정(#342). deterministic 계산 결과도 저장해 재기동과 정책 변경에서 고정한다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "recommendation_experiment_assignments",
        uniqueConstraints = @UniqueConstraint(name = "uk_rec_experiment_assignment_key_user",
                columnNames = {"experiment_key", "user_id"}))
public class RecommendationExperimentAssignment extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "experiment_key", length = 80, nullable = false, updatable = false)
    private String experimentKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "variant", length = 20, nullable = false, updatable = false)
    private RecommendationExperimentVariant variant;

    private RecommendationExperimentAssignment(User user, String experimentKey,
                                               RecommendationExperimentVariant variant) {
        this.user = user;
        this.experimentKey = experimentKey;
        this.variant = variant;
    }

    public static RecommendationExperimentAssignment assign(User user, String experimentKey,
                                                              RecommendationExperimentVariant variant) {
        return new RecommendationExperimentAssignment(user, experimentKey, variant);
    }
}
