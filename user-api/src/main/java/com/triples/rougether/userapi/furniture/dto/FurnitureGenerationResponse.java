package com.triples.rougether.userapi.furniture.dto;

import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Action;
import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Status;
import java.time.Instant;

// 원본/검수 전 후보 key, 프롬프트, lease token은 사용자 응답에 노출하지 않음.
public record FurnitureGenerationResponse(String id, Status status, Action action, String assetKey,
        Long userItemId, int imageAttempts, int reviewAttempts, String decision, String reason,
        String failureCode, Instant expiresAt, Instant createdAt, Instant updatedAt) {
    public static FurnitureGenerationResponse from(FurnitureGenerationJob job) {
        return new FurnitureGenerationResponse(job.getId(), job.getStatus(), job.getAction(),
                job.getResultAssetKey(), job.getUserItemId(), job.getImageAttempts(), job.getReviewAttempts(),
                job.getLastDecision(), job.getLastReason(), job.getFailureCode(),
                job.getExpiresAt(), job.getCreatedAt(), job.getUpdatedAt());
    }
}
