package com.triples.rougether.userapi.house.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record HouseCoverImageListResponse(List<HouseCoverImage> items) {

    public record HouseCoverImage(
            @Schema(description = "프론트 식별용 집 커버 코드", example = "cloud_balloon")
            String code,
            @Schema(description = "화면에 표시할 집 커버 이름", example = "구름 풍선 집")
            String name,
            @Schema(
                    description = "집 커버 이미지 asset key. CDN base URL과 조합해 이미지 URL로 사용",
                    example = "house/cloud-balloon/house-unified-cloud-balloon-frame.png")
            String coverImageKey) {
    }
}
