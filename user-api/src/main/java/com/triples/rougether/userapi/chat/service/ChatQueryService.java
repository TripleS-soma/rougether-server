package com.triples.rougether.userapi.chat.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.chat.entity.ChatImage;
import com.triples.rougether.domain.chat.entity.ChatMessage;
import com.triples.rougether.domain.chat.entity.ChatMessageReaction;
import com.triples.rougether.domain.chat.entity.ChatReadState;
import com.triples.rougether.domain.chat.entity.ChatRoom;
import com.triples.rougether.domain.chat.repository.ChatImageRepository;
import com.triples.rougether.domain.chat.repository.ChatMessageReactionRepository;
import com.triples.rougether.domain.chat.repository.ChatMessageRepository;
import com.triples.rougether.domain.chat.repository.ChatReadStateRepository;
import com.triples.rougether.domain.chat.repository.ChatRoomRepository;
import com.triples.rougether.userapi.chat.dto.ChatMessageListResponse;
import com.triples.rougether.userapi.chat.dto.ChatNotificationResponse;
import com.triples.rougether.userapi.chat.dto.ChatMessageResponse;
import com.triples.rougether.userapi.chat.dto.ChatReactionResponse;
import com.triples.rougether.userapi.chat.dto.ChatReactionsResponse;
import com.triples.rougether.userapi.chat.dto.ChatRoomResponse;
import com.triples.rougether.userapi.chat.error.ChatErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final ChatImageRepository images;
    private final ChatMessageReactionRepository reactions;
    private final ChatReadStateRepository reads;
    private final ChatAccessPolicy access;

    public ChatRoomResponse get(Long userId, Long roomId) {
        ChatRoom room = requireRoom(roomId);
        access.requireReadable(room, userId);
        return snapshot(room);
    }

    // 내 방별 채팅 푸시 음소거 상태(#435). 읽음 상태 행이 없으면 기본값(알림 받음).
    public ChatNotificationResponse notification(Long userId, Long roomId) {
        ChatRoom room = requireRoom(roomId);
        access.requireReadable(room, userId);
        boolean muted = reads.findByRoomIdAndUserIdIn(roomId, List.of(userId)).stream()
                .anyMatch(ChatReadState::isPushMuted);
        return new ChatNotificationResponse(roomId, muted);
    }

    // 소켓 쓰기 직전 호출하며 이전 페이지 조회의 멤버십 스냅샷을 재사용하지 않음.
    public void requireReadable(Long userId, Long roomId) {
        access.requireReadable(requireRoom(roomId), userId);
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
        var imagesByMessage = imagesOf(page);
        var reactionsByMessage = reactionsOf(page);
        return new ChatMessageListResponse(page.stream()
                .map(m -> ChatMessageResponse.of(m, imagesByMessage.get(m.getId()),
                        reactionsByMessage.get(m.getId()), state)).toList(),
                hasNext ? page.getLast().getSequence() : null, hasNext, state);
    }

    // 한 메시지의 현재 공감. 호출한 쪽이 권한과 메시지 상태를 이미 확인한 경우에 씀.
    public ChatReactionsResponse reactionsOf(ChatMessage message) {
        return new ChatReactionsResponse(message.getId(), message.getSequence(),
                ChatReactionResponse.of(reactions.findVisibleByMessageIdIn(List.of(message.getId()))));
    }

    // 내부 브로드캐스트 전용. 소켓이 프레임을 쓰기 직전에 최신 값을 읽음. 없거나 삭제된 메시지는 비어 있음.
    public Optional<ChatReactionsResponse> liveReactions(Long roomId, Long messageId) {
        return messages.findById(messageId)
                .filter(message -> message.getRoomId().equals(roomId) && !message.isDeleted())
                .map(this::reactionsOf);
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

    // 사진 메시지가 있는 페이지만 한 번에 조회함. 메시지마다 조회하지 않음. 메시지별 목록은 보낸 순서임.
    private Map<Long, List<ChatImage>> imagesOf(List<ChatMessage> page) {
        List<Long> ids = page.stream().filter(m -> m.isImage() && !m.isDeleted()).map(ChatMessage::getId).toList();
        if (ids.isEmpty()) return Map.of();
        return images.findWithOwnerByMessageIdIn(ids).stream().collect(Collectors.groupingBy(ChatImage::getMessageId));
    }

    // 공감도 페이지 단위로 한 번만 조회함. 메시지별 목록은 누른 순서임.
    private Map<Long, List<ChatMessageReaction>> reactionsOf(List<ChatMessage> page) {
        List<Long> ids = page.stream().filter(m -> !m.isDeleted()).map(ChatMessage::getId).toList();
        if (ids.isEmpty()) return Map.of();
        return reactions.findVisibleByMessageIdIn(ids).stream()
                .collect(Collectors.groupingBy(ChatMessageReaction::getMessageId));
    }

    private ChatRoom requireRoom(Long roomId) {
        return rooms.findById(roomId).orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
    }
}
