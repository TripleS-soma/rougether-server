package com.triples.rougether.userapi.category.dto;

import com.triples.rougether.domain.routine.entity.PrivacyScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record CategoryUpdateRequest(
        @Schema(description = "카테고리 이름(최대 100자). 미지정(null)이거나 공백이면 기존 값 유지", example = "운동")
        @Size(max = 100) String name,
        @Schema(description = "표시 색상 hex. 미지정(null)이면 기존 값 유지", example = "#FF8800")
        @Size(max = 20) String colorHex,
        @Schema(description = "아이콘 asset key. CDN base URL과 조합해 이미지 URL로 사용. 미지정(null)이면 기존 값 유지", example = "icon_health")
        @Size(max = 100) String iconKey,
        @Schema(description = "정렬 순서(0 이상, 작을수록 먼저). 미지정(null)이면 기존 값 유지", example = "0")
        @Min(0) Integer sortOrder,
        @Schema(description = "공개 범위. 허용값: PRIVATE(비공개), FRIENDS(친한친구), HOUSE(집), PUBLIC(공개). 미지정(null)이면 기존 값 유지", example = "PRIVATE")
        PrivacyScope visibility
) {
}
