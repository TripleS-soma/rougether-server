package com.triples.rougether.userapi.chat.dto;

import com.triples.rougether.domain.chat.entity.ChatImage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.time.Instant;

// 메시지에 붙은 사진 정보. 바이트는 GET /chat/images/{imageId}로 읽음. 탈퇴한 사람의 사진은 내보내지 않음.
public record ChatMessageImageResponse(
        @Schema(description = "사진 ID. GET /api/v1/chat/images/{imageId}의 {imageId}로 사용", example = "12")
        Long imageId,
        @Schema(description = "가로 크기(px)", example = "1600")
        int width,
        @Schema(description = "세로 크기(px)", example = "1200")
        int height,
        @Schema(description = "열람 만료 시각(UTC). 전송 시각부터 30일, 만료 후 기기 캐시도 표시하지 않음")
        Instant expiresAt) {
    public static ChatMessageImageResponse of(ChatImage image) {
        if (image == null || image.getOwner().isDeleted()) return null;
        return new ChatMessageImageResponse(image.getId(), image.getWidth(), image.getHeight(), image.getExpiresAt());
    }

    // images는 보낸 순서로 받음.
    public static List<ChatMessageImageResponse> listOf(List<ChatImage> images, Instant sentAt) {
        if (images == null) return List.of();
        return images.stream().filter(image -> !image.getOwner().isDeleted())
                .map(image -> new ChatMessageImageResponse(image.getId(), image.getWidth(), image.getHeight(), sentAt.plus(ChatImage.SENT_RETENTION))).toList();
    }
}
