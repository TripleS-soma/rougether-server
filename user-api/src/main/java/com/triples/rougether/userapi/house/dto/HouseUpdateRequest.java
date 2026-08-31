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
        @Schema(description = "GET /api/v1/houses/cover-images에서 선택한 커버 이미지 asset key (미지정 시 유지). 프론트는 CDN base URL과 조합해 사용",
                example = "house/cloud-balloon/house-unified-cloud-balloon-frame.png")
        String coverImageKey,
        @Schema(description = "최대 구성원 수 (1~10, 현재 인원 미만 불가, 미지정 시 유지)", example = "6")
        @Min(1) @Max(10) Integer maxMembers,
        @Schema(description = "공개 여부 (미지정 시 유지). true면 집 탐색·비구성원 미리보기에 노출, false면 초대코드로만 참여 가능. "
                + "가입 시 지급되는 기본 집은 비공개로 시작하며 여기서 공개로 전환할 수 있음", example = "true")
        Boolean isPublic) {
}
