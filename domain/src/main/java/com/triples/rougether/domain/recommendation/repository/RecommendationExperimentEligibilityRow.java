package com.triples.rougether.domain.recommendation.repository;

import com.triples.rougether.domain.recommendation.entity.RecommendationExperimentVariant;
import java.time.LocalDate;

public interface RecommendationExperimentEligibilityRow {

    Long getUserId();

    LocalDate getCohortWeekStart();

    RecommendationExperimentVariant getVariant();
}
