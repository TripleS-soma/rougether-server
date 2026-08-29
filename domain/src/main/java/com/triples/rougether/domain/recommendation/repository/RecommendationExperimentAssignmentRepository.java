package com.triples.rougether.domain.recommendation.repository;

import com.triples.rougether.domain.recommendation.entity.RecommendationExperimentAssignment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationExperimentAssignmentRepository
        extends JpaRepository<RecommendationExperimentAssignment, Long> {

    Optional<RecommendationExperimentAssignment> findByExperimentKeyAndUserId(String experimentKey, Long userId);
}
