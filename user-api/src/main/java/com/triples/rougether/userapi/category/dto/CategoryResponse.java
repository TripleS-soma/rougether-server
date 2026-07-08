package com.triples.rougether.userapi.category.dto;

import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import io.swagger.v3.oas.annotations.media.Schema;

public record CategoryResponse(
        @Schema(description = "카테고리 ID. 루틴/투두의 categoryId와 카테고리 API 경로의 {id}로 사용", example = "1")
        Long id,
        @Schema(description = "카테고리 이름", example = "운동")
        String name,
        @Schema(description = "표시 색상 hex", example = "#FF8800")
        String colorHex,
        @Schema(description = "아이콘 asset key. CDN base URL과 조합해 이미지 URL로 사용", example = "icon_health")
        String iconKey,
        @Schema(description = "정렬 순서(작을수록 먼저). 목록 조회는 이 값 오름차순으로 반환됨", example = "0")
        int sortOrder,
        @Schema(description = "공개 범위. 허용값: PRIVATE(비공개), FRIENDS(친한친구), HOUSE(집), PUBLIC(공개)", example = "PRIVATE")
        PrivacyScope visibility,
        @Schema(description = "삭제 여부. includeDeleted=true로 조회할 때만 true가 섞여 반환됩니다", example = "false")
        boolean deleted
) {

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getName(),
                category.getColorHex(),
                category.getIconKey(),
                category.getSortOrder(),
                category.getVisibility(),
                category.getDeletedAt() != null
        );
    }
}
