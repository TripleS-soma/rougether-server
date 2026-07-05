package com.triples.rougether.userapi.house.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

// POST /api/v1/houses 요청. maxMembers 미지정 시 기본 4.
public record HouseCreateRequest(
        @Schema(description = "집 이름 (2~30자)", example = "아침 루틴 하우스")
        @NotBlank @Size(min = 2, max = 30) String name,
        @Schema(description = "집 소개", example = "같이 아침 루틴 지켜요")
        String description,
        @Schema(description = "커버 이미지 asset key", example = "house/1f9d1c2e.png")
        String coverImageKey,
        @Schema(description = "최대 구성원 수 (1~10, 미지정 시 4)", example = "4")
        @Min(1) @Max(10) Integer maxMembers,
        @Schema(description = "집 목표 goal ID 목록 (필수 1~3개, 활성 goal 만 허용)", example = "[1, 2]")
        @NotEmpty @Size(max = 3) List<Long> goalIds) {
}
