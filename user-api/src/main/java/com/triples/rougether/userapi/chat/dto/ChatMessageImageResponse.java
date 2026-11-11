package com.triples.rougether.userapi.chat.dto;

import com.triples.rougether.domain.chat.entity.ChatImage;
import io.swagger.v3.oas.annotations.media.Schema;

// 메시지에 붙은 사진 정보. 바이트는 GET /chat/images/{imageId}로 읽음. 탈퇴한 사람의 사진은 내보내지 않음.
public record ChatMessageImageResponse(
        @Schema(description = "사진 ID. GET /api/v1/chat/images/{imageId}의 {imageId}로 사용", example = "12")
        Long imageId,
        @Schema(description = "가로 크기(px)", example = "1600")
        int width,
        @Schema(description = "세로 크기(px)", example = "1200")
        int height) {
    public static ChatMessageImageResponse of(ChatImage image) {
        if (image == null || image.getOwner().isDeleted()) return null;
        return new ChatMessageImageResponse(image.getId(), image.getWidth(), image.getHeight());
    }
}
