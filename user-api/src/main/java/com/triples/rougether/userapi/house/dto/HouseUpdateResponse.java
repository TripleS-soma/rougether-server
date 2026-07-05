package com.triples.rougether.userapi.house.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// 집 설정 수정 응답 - 갱신된 설정.
public record HouseUpdateResponse(
        @Schema(description = "집 ID", example = "1")
        Long houseId,
        @Schema(description = "집 이름", example = "저녁 루틴 하우스")
        String name,
        @Schema(description = "집 소개", example = "저녁 루틴으로 바꿨어요")
        String description,
        @Schema(description = "커버 이미지 asset key. CDN base URL 과 조합해 이미지 URL 로 사용", example = "house/2a8e3f1b.png")
        String coverImageKey,
        @Schema(description = "최대 구성원 수", example = "6")
        Integer maxMembers) {
}
