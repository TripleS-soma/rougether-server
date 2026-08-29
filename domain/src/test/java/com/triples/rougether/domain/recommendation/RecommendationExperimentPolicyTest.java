package com.triples.rougether.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.recommendation.entity.RecommendationExperimentVariant;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class RecommendationExperimentPolicyTest {

    @Test
    void 같은_실험과_사용자는_항상_같은_버킷과_variant로_배정한다() {
        long userId = 42L;

        int firstBucket = RecommendationExperimentPolicy.bucket(
                RecommendationExperimentPolicy.ROUTINE_ADJUSTMENT_V1, userId);

        assertThat(RecommendationExperimentPolicy.bucket(
                RecommendationExperimentPolicy.ROUTINE_ADJUSTMENT_V1, userId)).isEqualTo(firstBucket);
        assertThat(RecommendationExperimentPolicy.assign(
                RecommendationExperimentPolicy.ROUTINE_ADJUSTMENT_V1, userId))
                .isEqualTo(firstBucket < RecommendationExperimentPolicy.CONTROL_PERCENT
                        ? RecommendationExperimentVariant.CONTROL
                        : RecommendationExperimentVariant.TREATMENT);
    }

    @Test
    void 전체_100개_버킷_중_20개를_CONTROL로_사용한다() {
        long controlBuckets = LongStream.range(0, 100)
                .filter(bucket -> RecommendationExperimentPolicy.variantForBucket((int) bucket)
                        == RecommendationExperimentVariant.CONTROL)
                .count();

        assertThat(controlBuckets).isEqualTo(20);
    }
}
