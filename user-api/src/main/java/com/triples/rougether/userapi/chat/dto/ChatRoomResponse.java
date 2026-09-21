package com.triples.rougether.userapi.chat.dto;

import com.triples.rougether.domain.chat.entity.ChatRoomType;
import java.util.List;

public record ChatRoomResponse(Long roomId, ChatRoomType roomType, Long houseId, long lastSequence,
                               List<Reader> readers) {
    public record Reader(Long userId, Long membershipId, long lastReadSequence) {}

    public boolean includes(Long userId) {
        return readers.stream().anyMatch(r -> r.userId().equals(userId));
    }

    public long unreadCount(Long senderId, long sequence) {
        return readers.stream().filter(r -> !r.userId().equals(senderId) && r.lastReadSequence() < sequence).count();
    }
}
