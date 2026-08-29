package com.triples.rougether.batch.recommendation;

import com.triples.rougether.domain.recommendation.entity.RecommendationExperimentAssignment;
import com.triples.rougether.domain.recommendation.entity.RecommendationExperimentEligibility;
import com.triples.rougether.domain.recommendation.entity.RoutineRecommendation;
import java.util.List;

// userId 는 skip 로그 추적용으로 항상 담는다 - 구성 필드(배정·적격·추천)에서 역산하면 조합에 따라 null 이 된다.
record RecommendationProcessingResult(
        Long userId,
        RecommendationExperimentAssignment newAssignment,
        RecommendationExperimentEligibility eligibility,
        List<RoutineRecommendation> recommendations) {
}
