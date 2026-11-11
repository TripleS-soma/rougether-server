package com.triples.rougether.userapi.chat.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.chat.entity.ChatImage;
import com.triples.rougether.domain.chat.entity.ChatMessage;
import com.triples.rougether.domain.chat.entity.ChatReadState;
import com.triples.rougether.domain.chat.entity.ChatRoom;
import com.triples.rougether.domain.chat.repository.ChatImageRepository;
import com.triples.rougether.domain.chat.repository.ChatMessageRepository;
import com.triples.rougether.domain.chat.repository.ChatReadStateRepository;
import com.triples.rougether.domain.chat.repository.ChatRoomRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.userapi.chat.dto.ChatMessageResponse;
import com.triples.rougether.userapi.chat.dto.ChatNotificationResponse;
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
    private com.triples.rougether.userapi.aiuser.service.AiUserEvents aiEvents;
    @org.springframework.beans.factory.annotation.Autowired
    void configureAiEvents(com.triples.rougether.userapi.aiuser.service.AiUserEvents events) { aiEvents = events; }
    private final ChatRoomRepository rooms;
    private final ChatMessageRepository messages;
    private final ChatImageRepository images;
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
        validate(request);
        String key = request.clientMessageId().toString();
        var previous = messages.findByRoomIdAndSenderIdAndClientMessageId(roomId, userId, key);
        if (previous.isPresent()) return retried(previous.get(), request, room);
        return request.imageId() == null
                ? sendText(sender, room, key, request.content())
                : sendImage(sender, room, key, request.imageId());
    }

    private static void validate(ChatSendRequest request) {
        if (request == null || request.clientMessageId() == null) invalid();
        boolean hasContent = request.content() != null;
        if (hasContent == (request.imageId() != null)) invalid();
        if (hasContent && (request.content().isBlank() || request.content().length() > 2000)) invalid();
    }

    private ChatMessageResponse retried(ChatMessage previous, ChatSendRequest request, ChatRoom room) {
        ChatImage image = previous.isImage() ? images.findByMessageId(previous.getId()).orElse(null) : null;
        boolean same = request.imageId() == null
                ? !previous.isImage() && previous.getContent().equals(request.content())
                : previous.isImage() && (image == null || image.getId().equals(request.imageId()));
        if (!same) throw new BusinessException(ChatErrorCode.CHAT_MESSAGE_CONFLICT);
        return ChatMessageResponse.of(previous, image, query.snapshot(room));
    }

    private ChatMessageResponse sendText(User sender, ChatRoom room, String key, String content) {
        if (bannedWords.containsBannedWord(content))
            throw new BusinessException(ChatErrorCode.CHAT_CONTENT_BANNED);
        ChatMessage message = messages.save(ChatMessage.send(room, sender, key, content, clock.instant()));
        if (aiEvents != null) aiEvents.chat(sender, room.getId(), message.getId());
        return published(message, null, room);
    }

    // 사진 행을 잠근 채 메시지를 저장하고 연결함. 실패하면 메시지와 연결이 함께 롤백됨.
    private ChatMessageResponse sendImage(User sender, ChatRoom room, String key, Long imageId) {
        // 잠금을 기다리는 동안 취소된 사진이 통과하지 않도록 잠금 뒤의 시각으로 판정함.
        ChatImage image = images.findForUpdate(imageId)
                .filter(found -> found.isAttachableBy(sender.getId(), room.getId(), clock.instant()))
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_IMAGE_UNAVAILABLE));
        ChatMessage message = messages.save(ChatMessage.sendImage(room, sender, key, clock.instant()));
        image.attach(message.getId());
        return published(message, image, room);
    }

    private ChatMessageResponse published(ChatMessage message, ChatImage image, ChatRoom room) {
        // 발신만으로 이전 메시지를 읽었다고 간주하지 않음. 화면에 표시된 지점은 read API로 명시함.
        events.publishEvent(new ChatRoomChanged(room.getId()));
        events.publishEvent(new ChatMessageSent(message.getId()));
        return ChatMessageResponse.of(message, image, query.snapshot(room));
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

    // 방별 채팅 푸시 음소거(#435). 읽음 상태 행에 함께 저장하고, 행이 없으면 만듦(읽음 커서는 0 그대로).
    public ChatNotificationResponse changePushMuted(Long userId, Long roomId, boolean muted) {
        access.lockWriter(roomId, userId);
        lockRoom(roomId);
        ChatReadState state = reads.findWithLock(roomId, userId)
                .orElseGet(() -> reads.save(ChatReadState.unread(roomId, userId)));
        state.changePushMuted(muted);
        return new ChatNotificationResponse(roomId, muted);
    }

    private ChatRoom lockRoom(Long roomId) {
        return rooms.findWithLockById(roomId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
    }

    private static void invalid() { throw new BusinessException(ChatErrorCode.CHAT_INPUT_INVALID); }
}
