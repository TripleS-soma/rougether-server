package com.triples.rougether.userapi.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// 공감 설정·취소 응답. 소켓 MESSAGE_REACTIONS 프레임도 같은 값을 실음.
public record ChatReactionsResponse(
        @Schema(description = "메시지 ID", example = "120")
        Long messageId,
        @Schema(description = "메시지의 방별 순번. 공감으로 바뀌지 않음", example = "31")
        long sequence,
        @Schema(description = "이 메시지의 공감 전체(유형별). 공감이 없으면 빈 배열")
        List<ChatReactionResponse> reactions) {}
