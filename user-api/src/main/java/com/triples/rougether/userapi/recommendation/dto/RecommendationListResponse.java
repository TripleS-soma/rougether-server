package com.triples.rougether.userapi.recommendation.dto;

import java.util.List;

// 필드 1개 단순 래퍼라 @Schema 생략(add-swagger 컨벤션)
public record RecommendationListResponse(
        List<RecommendationItem> items
) {
}
