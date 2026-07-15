package com.triples.rougether.userapi.house.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record HouseCoverImageListResponse(List<HouseCoverImage> items) {

    public record HouseCoverImage(
            @Schema(
                    description = "집 커버 이미지 asset key. CDN base URL과 조합해 이미지 URL로 사용",
                    example = "house/cloud-balloon/house-unified-cloud-balloon-frame.png")
            String coverImageKey) {
    }
}
