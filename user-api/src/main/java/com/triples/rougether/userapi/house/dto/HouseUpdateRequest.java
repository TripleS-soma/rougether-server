package com.triples.rougether.userapi.house.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

// PUT /api/v1/houses/{houseId} 요청. 부분 수정 - 보내지 않은(null) 필드는 변경하지 않는다.
public record HouseUpdateRequest(
        @Schema(description = "집 이름 (2~30자, 미지정 시 유지)", example = "저녁 루틴 하우스")
        @Size(min = 2, max = 30) String name,
        @Schema(description = "집 소개 (미지정 시 유지)", example = "저녁 루틴으로 바꿨어요")
        String description,
        @Schema(description = "커버 이미지 asset key (미지정 시 유지)", example = "house/2a8e3f1b.png")
        @Size(max = 255) String coverImageKey,
        @Schema(description = "최대 구성원 수 (1~10, 현재 인원 미만 불가, 미지정 시 유지)", example = "6")
        @Min(1) @Max(10) Integer maxMembers) {
}
