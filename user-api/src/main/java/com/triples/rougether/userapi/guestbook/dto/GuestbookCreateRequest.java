package com.triples.rougether.userapi.guestbook.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// POST /api/v1/rooms/{roomOwnerId}/guestbooks 요청.
public record GuestbookCreateRequest(
        @Schema(description = "집 ID (방 주인과 작성자가 함께 속한 집). GET /api/v1/me/houses (내 집 목록) 응답의 houseId 값", example = "1")
        @NotNull Long houseId,
        @Schema(description = "방명록 내용 (1~500자)", example = "오늘도 루틴 완료! 방 예쁘다 ㅎㅎ")
        @NotBlank @Size(max = 500) String content) {
}
