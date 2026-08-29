package com.triples.rougether.domain.recommendation;

import com.triples.rougether.domain.recommendation.entity.RecommendationExperimentVariant;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

// AI 조정 추천 HOLDOUT(#342). 실험 키를 바꾸지 않는 한 사용자별 variant가 항상 같도록
// 표준 SHA-256으로 100개 버킷을 만든다. 실제 배정은 DB에도 영구 저장해 정책 변경과 재기동에서 보호한다.
public final class RecommendationExperimentPolicy {

    public static final String ROUTINE_ADJUSTMENT_V1 = "ROUTINE_ADJUSTMENT_V1";
    public static final int CONTROL_PERCENT = 20;
    private static final int BUCKET_COUNT = 100;

    private RecommendationExperimentPolicy() {
    }

    public static RecommendationExperimentVariant assign(String experimentKey, long userId) {
        return variantForBucket(bucket(experimentKey, userId));
    }

    public static RecommendationExperimentVariant variantForBucket(int bucket) {
        if (bucket < 0 || bucket >= BUCKET_COUNT) {
            throw new IllegalArgumentException("bucket은 0 이상 100 미만이어야 합니다.");
        }
        return bucket < CONTROL_PERCENT
                ? RecommendationExperimentVariant.CONTROL
                : RecommendationExperimentVariant.TREATMENT;
    }

    public static int bucket(String experimentKey, long userId) {
        byte[] digest = sha256(experimentKey + ":" + userId);
        int firstFourBytes = ByteBuffer.wrap(digest).getInt();
        return Integer.remainderUnsigned(firstFourBytes, BUCKET_COUNT);
    }

    private static byte[] sha256(String source) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM이 SHA-256을 지원하지 않습니다.", exception);
        }
    }
}
