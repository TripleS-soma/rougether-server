package com.triples.rougether.adminapi.assetfoundry.dto;

import java.util.List;

public record AssetPipelineQaUpdateRequest(
        Long expectedRevision,
        List<AssetPipelineQaCheckRequest> checks
) {
}
