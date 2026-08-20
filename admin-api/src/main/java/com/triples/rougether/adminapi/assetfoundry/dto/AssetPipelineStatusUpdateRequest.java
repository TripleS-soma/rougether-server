package com.triples.rougether.adminapi.assetfoundry.dto;

import com.triples.rougether.domain.assetpipeline.entity.AssetPipelineStatus;

public record AssetPipelineStatusUpdateRequest(
        Long expectedRevision,
        AssetPipelineStatus targetStatus,
        String note
) {
}
