package com.triples.rougether.userapi.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

// content와 imageId 중 정확히 하나만 받음. 조합 검증은 서비스에서 CHAT_INPUT_INVALID로 처리함.
public record ChatSendRequest(
        @Schema(description = "클라이언트가 만든 요청 식별자(UUID). 같은 값으로 다시 보내면 처음 메시지를 그대로 돌려줌",
                example = "3f2b8c1e-5a7d-4c9e-8f10-2b6d7e9a1c34")
        @NotNull UUID clientMessageId,
        @Schema(description = "글 메시지 본문(공백만 불가, 최대 2,000자). imageId와 둘 중 하나만 보냄",
                example = "오늘 루틴 다 했어?", nullable = true)
        @Size(max = 2000) String content,
        @Schema(description = "보낼 사진 ID. POST /api/v1/chat/rooms/{roomId}/images 응답의 imageId 값. content와 둘 중 하나만 보냄",
                example = "12", nullable = true)
        Long imageId) {
    public ChatSendRequest(UUID clientMessageId, String content) {
        this(clientMessageId, content, null);
    }
}
