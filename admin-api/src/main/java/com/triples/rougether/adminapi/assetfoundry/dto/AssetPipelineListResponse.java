package com.triples.rougether.adminapi.assetfoundry.dto;

import java.util.List;
import java.util.Map;

public record AssetPipelineListResponse(
        List<AssetPipelineJobResponse> items,
        Map<String, Long> counts
) {
}
