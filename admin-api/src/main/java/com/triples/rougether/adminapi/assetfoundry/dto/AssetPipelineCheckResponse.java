package com.triples.rougether.adminapi.assetfoundry.dto;

import com.triples.rougether.domain.assetpipeline.entity.AssetPipelineCheck;
import com.triples.rougether.domain.assetpipeline.entity.AssetPipelineCheckResult;

public record AssetPipelineCheckResponse(
        String code,
        String label,
        AssetPipelineCheckResult result,
        Integer score,
        boolean required,
        String message
) {

    public static AssetPipelineCheckResponse from(AssetPipelineCheck check) {
        return new AssetPipelineCheckResponse(
                check.getCode(),
                check.getLabel(),
                check.getResult(),
                check.getScore(),
                check.isRequired(),
                check.getMessage());
    }
}
