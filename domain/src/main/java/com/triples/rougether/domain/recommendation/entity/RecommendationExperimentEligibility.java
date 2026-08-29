package com.triples.rougether.domain.recommendation.entity;

import com.triples.rougether.domain.support.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 추천 생성 주(KST 일요일 시작)별 적격 진입 기록(#342). 추천 row가 없는 CONTROL의 측정 분모다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "recommendation_experiment_eligibilities",
        uniqueConstraints = @UniqueConstraint(name = "uk_rec_experiment_eligibility_assignment_week",
                columnNames = {"assignment_id", "cohort_week_start"}))
public class RecommendationExperimentEligibility extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false, updatable = false)
    private RecommendationExperimentAssignment assignment;

    @Column(name = "cohort_week_start", nullable = false, updatable = false)
    private LocalDate cohortWeekStart;

    private RecommendationExperimentEligibility(RecommendationExperimentAssignment assignment,
                                                LocalDate cohortWeekStart) {
        this.assignment = assignment;
        this.cohortWeekStart = cohortWeekStart;
    }

    public static RecommendationExperimentEligibility record(RecommendationExperimentAssignment assignment,
                                                              LocalDate cohortWeekStart) {
        return new RecommendationExperimentEligibility(assignment, cohortWeekStart);
    }
}
