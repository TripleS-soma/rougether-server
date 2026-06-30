package com.triples.rougether.userapi.category.dto;

import com.triples.rougether.domain.routine.entity.PrivacyScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CategoryCreateRequest(
        @Schema(description = "카테고리 이름", example = "운동")
        @NotBlank @Size(max = 100) String name,
        @Schema(description = "표시 색상 hex", example = "#FF8800")
        @Size(max = 20) String colorHex,
        @Schema(description = "아이콘 asset key", example = "icon_health")
        @Size(max = 100) String iconKey,
        @Schema(description = "정렬 순서(작을수록 먼저)", example = "0")
        @Min(0) Integer sortOrder,
        @Schema(description = "공개 범위")
        PrivacyScope visibility
) {
}
