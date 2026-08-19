package com.triples.rougether.adminapi.assetfoundry.dto;

import com.triples.rougether.domain.assetpipeline.entity.AssetPipelineEvent;
import com.triples.rougether.domain.assetpipeline.entity.AssetPipelineStatus;
import java.time.Instant;

public record AssetPipelineEventResponse(
        AssetPipelineStatus fromStatus,
        AssetPipelineStatus toStatus,
        String actor,
        String note,
        Instant createdAt
) {

    public static AssetPipelineEventResponse from(AssetPipelineEvent event) {
        return new AssetPipelineEventResponse(
                event.getFromStatus(),
                event.getToStatus(),
                event.getActor(),
                event.getNote(),
                event.getCreatedAt());
    }
}
