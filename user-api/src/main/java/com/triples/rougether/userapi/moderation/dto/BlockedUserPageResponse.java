package com.triples.rougether.userapi.moderation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// 피드와 같은 커서 Page 형식. nextCursor 는 차단 기록 ID(회원 ID 아님).
public record BlockedUserPageResponse(
        List<BlockedUserResponse> items,
        @Schema(description = "다음 페이지 cursor. 다음 페이지가 없으면 null", example = "31", nullable = true)
        Long nextCursor,
        @Schema(description = "다음 페이지 존재 여부", example = "false")
        boolean hasNext) {
}
