package com.triples.rougether.userapi.chat.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.chat.entity.ChatMessage;
import com.triples.rougether.domain.chat.entity.ChatReadState;
import com.triples.rougether.domain.chat.entity.ChatRoom;
import com.triples.rougether.domain.chat.repository.ChatMessageRepository;
import com.triples.rougether.domain.chat.repository.ChatReadStateRepository;
import com.triples.rougether.domain.chat.repository.ChatRoomRepository;
import com.triples.rougether.userapi.chat.dto.ChatMessageResponse;
import com.triples.rougether.userapi.chat.dto.ChatRoomResponse;
import com.triples.rougether.userapi.chat.dto.ChatSendRequest;
import com.triples.rougether.userapi.chat.error.ChatErrorCode;
import com.triples.rougether.userapi.global.text.BannedWordChecker;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ChatCommandService {
    private final ChatRoomRepository rooms;
    private final ChatMessageRepository messages;
    private final ChatReadStateRepository reads;
    private final ChatAccessPolicy access;
    private final ChatQueryService query;
    private final BannedWordChecker bannedWords;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ChatRoomResponse openHouse(Long userId, Long houseId) {
        access.lockHouseMember(houseId, userId);
        ChatRoom room = rooms.findWithLockByHouseId(houseId).orElseGet(() -> rooms.save(ChatRoom.forHouse(houseId)));
        return query.snapshot(room);
    }

    public ChatMessageResponse send(Long userId, Long roomId, ChatSendRequest request) {
        var sender = access.lockWriter(roomId, userId);
        ChatRoom room = lockRoom(roomId);
        if (request == null || request.clientMessageId() == null || request.content() == null
                || request.content().isBlank() || request.content().length() > 2000) invalid();
        String key = request.clientMessageId().toString();
        var previous = messages.findByRoomIdAndSenderIdAndClientMessageId(roomId, userId, key);
        if (previous.isPresent()) {
            if (!previous.get().getContent().equals(request.content()))
                throw new BusinessException(ChatErrorCode.CHAT_MESSAGE_CONFLICT);
            return ChatMessageResponse.of(previous.get(), query.snapshot(room));
        }
        if (bannedWords.containsBannedWord(request.content()))
            throw new BusinessException(ChatErrorCode.CHAT_CONTENT_BANNED);
        ChatMessage message = messages.save(ChatMessage.send(room, sender, key, request.content(), clock.instant()));
        // 발신만으로 이전 메시지를 읽었다고 간주하지 않음. 화면에 표시된 지점은 read API로 명시함.
        events.publishEvent(new ChatRoomChanged(roomId));
        return ChatMessageResponse.of(message, query.snapshot(room));
    }

    public ChatRoomResponse read(Long userId, Long roomId, long sequence) {
        access.lockWriter(roomId, userId);
        ChatRoom room = lockRoom(roomId);
        if (sequence < 0 || sequence > room.getLastSequence()) invalid();
        ChatReadState state = reads.findWithLock(roomId, userId)
                .orElseGet(() -> reads.save(ChatReadState.unread(roomId, userId)));
        if (state.advance(sequence)) events.publishEvent(new ChatRoomChanged(roomId));
        return query.snapshot(room);
    }

    private ChatRoom lockRoom(Long roomId) {
        return rooms.findWithLockById(roomId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
    }

    private static void invalid() { throw new BusinessException(ChatErrorCode.CHAT_INPUT_INVALID); }
}
