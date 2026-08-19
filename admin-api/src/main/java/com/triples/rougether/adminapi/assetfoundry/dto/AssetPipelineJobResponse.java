package com.triples.rougether.adminapi.assetfoundry.dto;

import com.triples.rougether.domain.assetpipeline.entity.AssetPipelineJob;
import com.triples.rougether.domain.assetpipeline.entity.AssetPipelineKind;
import com.triples.rougether.domain.assetpipeline.entity.AssetPipelineStatus;
import java.time.Instant;
import java.util.List;

public record AssetPipelineJobResponse(
        Long id,
        String name,
        String slug,
        AssetPipelineKind kind,
        AssetPipelineStatus status,
        AssetPipelineStatus nextStatus,
        String themeCode,
        String sourceAssetKey,
        String outputAssetKey,
        String previewAssetKey,
        Long targetItemId,
        String expectedOldAssetKey,
        Integer qualityScore,
        boolean qaPassed,
        String createdBy,
        String approvedBy,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        List<AssetPipelineCheckResponse> checks,
        List<AssetPipelineEventResponse> events
) {

    public static AssetPipelineJobResponse from(AssetPipelineJob job) {
        return new AssetPipelineJobResponse(
                job.getId(),
                job.getName(),
                job.getSlug(),
                job.getKind(),
                job.getStatus(),
                job.getStatus().next(),
                job.getThemeCode(),
                job.getSourceAssetKey(),
                job.getOutputAssetKey(),
                job.getPreviewAssetKey(),
                job.getTargetItemId(),
                job.getExpectedOldAssetKey(),
                job.getQualityScore(),
                job.isQaPassed(),
                job.getCreatedBy(),
                job.getApprovedBy(),
                job.getRevision(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getChecks().stream().map(AssetPipelineCheckResponse::from).toList(),
                job.getEvents().stream().map(AssetPipelineEventResponse::from).toList());
    }
}
