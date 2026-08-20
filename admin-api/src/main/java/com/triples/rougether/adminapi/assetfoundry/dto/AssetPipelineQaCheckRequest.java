package com.triples.rougether.adminapi.assetfoundry.dto;

import com.triples.rougether.domain.assetpipeline.entity.AssetPipelineCheckResult;

public record AssetPipelineQaCheckRequest(
        String code,
        String label,
        AssetPipelineCheckResult result,
        Integer score,
        boolean required,
        String message
) {
}
