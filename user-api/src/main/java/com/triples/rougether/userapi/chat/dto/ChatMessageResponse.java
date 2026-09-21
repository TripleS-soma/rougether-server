package com.triples.rougether.userapi.chat.dto;

import com.triples.rougether.domain.chat.entity.ChatMessage;
import java.time.Instant;

public record ChatMessageResponse(Long messageId, Long roomId, long sequence, String clientMessageId,
                                  Long senderUserId, String senderNickname, String senderProfileImageKey,
                                  String content, Instant createdAt, long unreadCount) {
    public static ChatMessageResponse of(ChatMessage message, ChatRoomResponse room) {
        var sender = message.getSender();
        return new ChatMessageResponse(message.getId(), message.getRoomId(), message.getSequence(),
                message.getClientMessageId(), sender.getId(), sender.isDeleted() ? null : sender.getNickname(),
                sender.isDeleted() ? null : sender.getProfileImageKey(), message.getContent(), message.getCreatedAt(),
                room.unreadCount(sender.getId(), message.getSequence()));
    }
}
