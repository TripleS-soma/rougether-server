package com.triples.rougether.userapi.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

// 글만, 사진(+설명 글), 이모티콘만 중 하나로 보냄. 조합 검증은 서비스에서 CHAT_INPUT_INVALID로 처리함.
public record ChatSendRequest(
        @Schema(description = "클라이언트가 만든 요청 식별자(UUID). 같은 값으로 다시 보내면 처음 메시지를 그대로 돌려줌",
                example = "3f2b8c1e-5a7d-4c9e-8f10-2b6d7e9a1c34")
        @NotNull UUID clientMessageId,
        @Schema(description = "글 메시지 본문 또는 사진에 붙이는 설명 글(공백만 불가, 최대 2,000자). 이모티콘과는 함께 보낼 수 없음",
                example = "오늘 루틴 다 했어?", nullable = true)
        @Size(max = 2000) String content,
        @Schema(description = "보낼 사진 ID 한 개(구버전 앱용). imageIds=[imageId]와 같게 처리하며 imageIds와 함께 보낼 수 없음",
                example = "12", nullable = true)
        Long imageId,
        @Schema(description = "보낼 사진 ID 목록(1~10장, 중복 불가). POST /api/v1/chat/rooms/{roomId}/images 응답의 imageId 값이며 "
                + "적힌 순서대로 메시지에 붙음", example = "[12, 13]", nullable = true)
        List<Long> imageIds,
        @Schema(description = "이모티콘 key(영문 소문자·숫자로 시작, 소문자·숫자·_·- 최대 40자). 그림은 앱에 들어 있고 "
                + "content·사진과 함께 보낼 수 없음", example = "ruti_hello", nullable = true)
        String emoticonKey) {
    public ChatSendRequest(UUID clientMessageId, String content) {
        this(clientMessageId, content, null, null, null);
    }

    public ChatSendRequest(UUID clientMessageId, String content, Long imageId) {
        this(clientMessageId, content, imageId, null, null);
    }
}
