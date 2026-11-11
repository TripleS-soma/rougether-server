package com.triples.rougether.userapi.chat.dto;

import com.triples.rougether.domain.chat.entity.ChatImage;
import com.triples.rougether.domain.chat.entity.ChatMessage;
import com.triples.rougether.domain.chat.entity.ChatMessageType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// messageType·image는 뒤에 덧붙인 필드임. 구버전 앱은 content만 읽어도 표시할 수 있음.
public record ChatMessageResponse(
        @Schema(description = "메시지 ID", example = "101")
        Long messageId,
        @Schema(description = "채팅방 ID", example = "7")
        Long roomId,
        @Schema(description = "방별 메시지 순번(1부터). 기록 조회의 before·after와 읽음 처리에 사용", example = "42")
        long sequence,
        @Schema(description = "전송 요청에 넣은 UUID. 보낸 쪽 화면의 임시 메시지와 맞출 때 사용",
                example = "3f2b8c1e-5a7d-4c9e-8f10-2b6d7e9a1c34")
        String clientMessageId,
        @Schema(description = "보낸 회원 ID", example = "3")
        Long senderUserId,
        @Schema(description = "보낸 회원 닉네임. 탈퇴한 회원이면 null", example = "루티", nullable = true)
        String senderNickname,
        @Schema(description = "보낸 회원 프로필 asset key. CDN base URL과 조합해 이미지 URL로 사용. 탈퇴했거나 없으면 null",
                nullable = true)
        String senderProfileImageKey,
        @Schema(description = "표시용 본문(항상 값이 있음). IMAGE는 구버전 앱용 \"사진을 보냈어요\", 운영자가 삭제한 메시지는 \"삭제된 메시지예요\"",
                example = "오늘 루틴 다 했어?")
        String content,
        @Schema(description = "보낸 시각(UTC)")
        Instant createdAt,
        @Schema(description = "이 메시지를 아직 읽지 않은 구성원 수(보낸 사람 제외)", example = "1")
        long unreadCount,
        @Schema(description = "메시지 유형. 허용값: TEXT(글), IMAGE(사진)", example = "IMAGE")
        ChatMessageType messageType,
        @Schema(description = "사진 정보. 글 메시지, 운영자가 삭제한 메시지, 탈퇴한 회원의 사진이면 null", nullable = true)
        ChatMessageImageResponse image) {
    public static ChatMessageResponse of(ChatMessage message, ChatImage image, ChatRoomResponse room) {
        var sender = message.getSender();
        return new ChatMessageResponse(message.getId(), message.getRoomId(), message.getSequence(),
                message.getClientMessageId(), sender.getId(), sender.isDeleted() ? null : sender.getNickname(),
                sender.isDeleted() ? null : sender.getProfileImageKey(),
                message.isDeleted() ? ChatMessage.DELETED_CONTENT : message.getContent(), message.getCreatedAt(),
                room.unreadCount(sender.getId(), message.getSequence()), message.getMessageType(),
                message.isDeleted() ? null : ChatMessageImageResponse.of(image));
    }
}
