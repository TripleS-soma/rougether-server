package com.triples.rougether.userapi.chat.dto;

import com.triples.rougether.domain.chat.entity.ChatImage;
import com.triples.rougether.domain.chat.entity.ChatMessage;
import com.triples.rougether.domain.chat.entity.ChatMessageReaction;
import com.triples.rougether.domain.chat.entity.ChatMessageType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

// messageType 이후는 뒤에 덧붙인 필드임. 구버전 앱은 content(와 image)만 읽어도 표시할 수 있음.
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
        @Schema(description = "구버전 앱 표시용 본문(항상 값이 있음). TEXT는 본문, IMAGE는 설명 글이 있으면 그 글이고 없으면 "
                + "\"사진을 보냈어요\"(여러 장은 \"사진 N장을 보냈어요\"), EMOTICON은 \"이모티콘을 보냈어요\", "
                + "운영자가 삭제한 메시지는 \"삭제된 메시지예요\"", example = "오늘 루틴 다 했어?")
        String content,
        @Schema(description = "보낸 시각(UTC)")
        Instant createdAt,
        @Schema(description = "이 메시지를 아직 읽지 않은 구성원 수(보낸 사람 제외)", example = "1")
        long unreadCount,
        @Schema(description = "메시지 유형. 허용값: TEXT(글), IMAGE(사진, 설명 글 포함 가능), EMOTICON(이모티콘)", example = "IMAGE")
        ChatMessageType messageType,
        @Schema(description = "첫 번째 사진 정보(구버전 앱용, images[0]과 같음). 사진 메시지가 아니거나 운영자가 삭제한 메시지, "
                + "탈퇴한 회원의 사진이면 null", nullable = true)
        ChatMessageImageResponse image,
        @Schema(description = "사진 목록(보낸 순서, 최대 10장). 글·이모티콘 메시지와 운영자가 삭제한 메시지는 빈 배열이고 "
                + "탈퇴한 회원의 사진은 빠짐")
        List<ChatMessageImageResponse> images,
        @Schema(description = "사진에 붙인 설명 글. 설명 글이 없거나 사진 메시지가 아니거나 운영자가 삭제한 메시지면 null",
                example = "오늘 아침 산책", nullable = true)
        String caption,
        @Schema(description = "이모티콘 key. 앱에 들어 있는 그림과 맞춰 표시하고 모르는 key는 content로 대신 표시함. "
                + "이모티콘 메시지가 아니거나 운영자가 삭제한 메시지면 null", example = "ruti_hello", nullable = true)
        String emoticonKey,
        @Schema(description = "공감 목록(유형별, 공감이 있는 유형만). 공감이 없거나 운영자가 삭제한 메시지면 빈 배열")
        List<ChatReactionResponse> reactions) {
    // images는 보낸 순서, reactions는 누른 순서로 받음. 삭제된 메시지는 원문·사진·공감을 내보내지 않음.
    public static ChatMessageResponse of(ChatMessage message, List<ChatImage> images,
                                         List<ChatMessageReaction> reactions, ChatRoomResponse room) {
        var sender = message.getSender();
        boolean deleted = message.isDeleted();
        List<ChatMessageImageResponse> photos = deleted ? List.of() : ChatMessageImageResponse.listOf(images);
        return new ChatMessageResponse(message.getId(), message.getRoomId(), message.getSequence(),
                message.getClientMessageId(), sender.getId(), sender.isDeleted() ? null : sender.getNickname(),
                sender.isDeleted() ? null : sender.getProfileImageKey(),
                deleted ? ChatMessage.DELETED_CONTENT : message.getContent(), message.getCreatedAt(),
                room.unreadCount(sender.getId(), message.getSequence()), message.getMessageType(),
                photos.isEmpty() ? null : photos.getFirst(), photos,
                deleted ? null : message.getCaption(), deleted ? null : message.getEmoticonKey(),
                deleted ? List.of() : ChatReactionResponse.of(reactions));
    }
}
