package com.triples.rougether.batch.recommendation;

import com.triples.rougether.domain.recommendation.entity.RecommendationExperimentAssignment;
import com.triples.rougether.domain.recommendation.entity.RecommendationExperimentEligibility;
import com.triples.rougether.domain.recommendation.entity.RoutineRecommendation;
import java.util.List;

record RecommendationProcessingResult(
        RecommendationExperimentAssignment newAssignment,
        RecommendationExperimentEligibility eligibility,
        List<RoutineRecommendation> recommendations) {
}
