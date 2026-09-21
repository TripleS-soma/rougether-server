package com.triples.rougether.userapi.chat.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.chat.entity.ChatRoom;
import com.triples.rougether.domain.chat.repository.ChatMessageRepository;
import com.triples.rougether.domain.chat.repository.ChatReadStateRepository;
import com.triples.rougether.domain.chat.repository.ChatRoomRepository;
import com.triples.rougether.userapi.chat.dto.ChatMessageListResponse;
import com.triples.rougether.userapi.chat.dto.ChatMessageResponse;
import com.triples.rougether.userapi.chat.dto.ChatRoomResponse;
import com.triples.rougether.userapi.chat.error.ChatErrorCode;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatQueryService {
    private final ChatRoomRepository rooms;
    private final ChatMessageRepository messages;
    private final ChatReadStateRepository reads;
    private final ChatAccessPolicy access;

    public ChatRoomResponse get(Long userId, Long roomId) {
        ChatRoom room = requireRoom(roomId);
        access.requireReadable(room, userId);
        return snapshot(room);
    }

    public ChatMessageListResponse messages(Long userId, Long roomId, Long before, Long after, int size) {
        if (size < 1 || size > 100 || (before != null && before <= 0) || (after != null && after < 0)
                || (before != null && after != null)) throw new BusinessException(ChatErrorCode.CHAT_INPUT_INVALID);
        ChatRoom room = requireRoom(roomId);
        access.requireReadable(room, userId);
        var found = after == null ? messages.findBefore(roomId, before, PageRequest.of(0, size + 1))
                : messages.findAfter(roomId, after, PageRequest.of(0, size + 1));
        boolean hasNext = found.size() > size;
        var page = hasNext ? found.subList(0, size) : found;
        var state = snapshot(room);
        return new ChatMessageListResponse(page.stream().map(m -> ChatMessageResponse.of(m, state)).toList(),
                hasNext ? page.getLast().getSequence() : null, hasNext, state);
    }

    // 내부 브로드캐스트 전용. HTTP 컨트롤러는 반드시 get/messages로 인가함.
    public ChatRoomResponse liveSnapshot(Long roomId) {
        ChatRoom room = requireRoom(roomId);
        return snapshot(room);
    }

    public ChatRoomResponse snapshot(ChatRoom room) {
        var participants = access.participants(room);
        var userIds = participants.stream().map(ChatAccessPolicy.Participant::userId).toList();
        Map<Long, Long> cursors = userIds.isEmpty() ? Map.of() : reads.findByRoomIdAndUserIdIn(room.getId(), userIds).stream()
                .collect(Collectors.toMap(s -> s.getUserId(), s -> s.getLastReadSequence()));
        var readers = participants.stream().map(p -> new ChatRoomResponse.Reader(p.userId(), p.membershipId(),
                cursors.getOrDefault(p.userId(), 0L))).toList();
        return new ChatRoomResponse(room.getId(), room.getRoomType(), room.getHouseId(), room.getLastSequence(), readers);
    }

    private ChatRoom requireRoom(Long roomId) {
        return rooms.findById(roomId).orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
    }
}
