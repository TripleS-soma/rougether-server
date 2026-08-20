package com.triples.rougether.adminapi.assetfoundry.dto;

import com.triples.rougether.domain.assetpipeline.entity.AssetPipelineKind;

public record AssetPipelineCreateRequest(
        String name,
        String slug,
        AssetPipelineKind kind,
        String themeCode,
        String sourceAssetKey,
        String outputAssetKey,
        String previewAssetKey,
        Long targetItemId,
        String expectedOldAssetKey
) {
}
