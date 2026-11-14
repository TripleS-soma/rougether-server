package com.triples.rougether.userapi.chat.dto;

import com.triples.rougether.domain.chat.entity.ChatImage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// 저장 key는 내보내지 않음. 실제 바이트는 인증된 채팅 사진 API로만 읽음.
public record ChatImageResponse(
        @Schema(description = "사진 ID. 메시지 전송의 imageId와 GET /api/v1/chat/images/{imageId}의 {imageId}로 사용", example = "12")
        Long imageId,
        @Schema(description = "변환 후 가로 크기(px)", example = "1600")
        int width,
        @Schema(description = "변환 후 세로 크기(px)", example = "1200")
        int height,
        @Schema(description = "저장된 사진 형식. 항상 image/jpeg", example = "image/jpeg")
        String contentType,
        @Schema(description = "열람 만료 시각(UTC). 미전송은 업로드 후 24시간, 전송 후에는 전송 시각부터 30일")
        Instant expiresAt) {
    public static ChatImageResponse of(ChatImage image) {
        return of(image, image.getExpiresAt());
    }

    public static ChatImageResponse of(ChatImage image, Instant expiresAt) {
        return new ChatImageResponse(image.getId(), image.getWidth(), image.getHeight(), "image/jpeg", expiresAt);
    }
}
