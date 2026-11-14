package com.triples.rougether.userapi.chat.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.chat.entity.ChatImage;
import com.triples.rougether.domain.chat.entity.ChatMessage;
import com.triples.rougether.domain.chat.entity.ChatMessageType;
import com.triples.rougether.domain.chat.entity.ChatReadState;
import com.triples.rougether.domain.chat.entity.ChatRoom;
import com.triples.rougether.domain.chat.repository.ChatImageRepository;
import com.triples.rougether.domain.chat.repository.ChatMessageReactionRepository;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ChatCommandService {
    private static final int MAX_CONTENT_LENGTH = 2000;
    private static final int MAX_IMAGES = 10;
    // 이모티콘 그림은 앱에 들어 있어 서버는 key 형식만 검사함.
    private static final Pattern EMOTICON_KEY = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,39}$");

    private com.triples.rougether.userapi.aiuser.service.AiUserEvents aiEvents;
    @org.springframework.beans.factory.annotation.Autowired
    void configureAiEvents(com.triples.rougether.userapi.aiuser.service.AiUserEvents events) { aiEvents = events; }
    private final ChatRoomRepository rooms;
    private final ChatMessageRepository messages;
    private final ChatImageRepository images;
    private final ChatMessageReactionRepository reactions;
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
        Draft draft = draft(request);
        String key = request.clientMessageId().toString();
        var previous = messages.findByRoomIdAndSenderIdAndClientMessageId(roomId, userId, key);
        if (previous.isPresent()) return retried(previous.get(), draft, room);
        return switch (draft.type()) {
            case TEXT -> sendText(sender, room, key, draft.content());
            case IMAGE -> sendImages(sender, room, key, draft);
            case EMOTICON -> published(messages.save(
                    ChatMessage.sendEmoticon(room, sender, key, draft.emoticonKey(), clock.instant())), List.of(), room);
        };
    }

    // 검증을 마친 전송 내용. content는 TEXT의 본문 또는 IMAGE의 설명 글(없으면 null)임.
    private record Draft(ChatMessageType type, String content, List<Long> imageIds, String emoticonKey) {}

    // 글만, 사진(+설명 글), 이모티콘만 중 하나만 허용함. imageId는 imageIds=[imageId]와 같게 다룸.
    private static Draft draft(ChatSendRequest request) {
        if (request == null || request.clientMessageId() == null) invalid();
        boolean listed = request.imageIds() != null && !request.imageIds().isEmpty();
        if (request.imageId() != null && listed) invalid();
        List<Long> imageIds = request.imageId() != null ? List.of(request.imageId())
                : listed ? request.imageIds() : List.of();
        String content = request.content();
        if (content != null && (content.isBlank() || content.length() > MAX_CONTENT_LENGTH)) invalid();
        if (request.emoticonKey() != null) {
            if (content != null || !imageIds.isEmpty() || !EMOTICON_KEY.matcher(request.emoticonKey()).matches()) invalid();
            return new Draft(ChatMessageType.EMOTICON, null, List.of(), request.emoticonKey());
        }
        if (!imageIds.isEmpty()) {
            if (imageIds.size() > MAX_IMAGES || imageIds.stream().anyMatch(Objects::isNull)
                    || new HashSet<>(imageIds).size() != imageIds.size()) invalid();
            return new Draft(ChatMessageType.IMAGE, content, List.copyOf(imageIds), null);
        }
        if (content == null) invalid();
        return new Draft(ChatMessageType.TEXT, content, List.of(), null);
    }

    private ChatMessageResponse retried(ChatMessage previous, Draft draft, ChatRoom room) {
        List<ChatImage> attached = previous.isImage()
                ? images.findWithOwnerByMessageIdIn(List.of(previous.getId())) : List.of();
        if (!same(previous, draft, attached)) throw new BusinessException(ChatErrorCode.CHAT_MESSAGE_CONFLICT);
        return ChatMessageResponse.of(previous, attached,
                reactions.findVisibleByMessageIdIn(List.of(previous.getId())), query.snapshot(room));
    }

    // 유형, 글(설명 글 포함), 사진 ID 순서, 이모티콘 key가 모두 같아야 같은 요청으로 봄.
    private static boolean same(ChatMessage previous, Draft draft, List<ChatImage> attached) {
        if (previous.getMessageType() != draft.type()) return false;
        return switch (draft.type()) {
            case TEXT -> previous.getContent().equals(draft.content());
            case EMOTICON -> draft.emoticonKey().equals(previous.getEmoticonKey());
            // 운영자가 삭제해 사진 행이 정리되는 메시지는 사진을 비교할 수 없으므로 글만 비교함.
            case IMAGE -> Objects.equals(previous.getCaption(), draft.content())
                    && (previous.isDeleted() || attached.isEmpty()
                    || attached.stream().map(ChatImage::getId).toList().equals(draft.imageIds()));
        };
    }

    private ChatMessageResponse sendText(User sender, ChatRoom room, String key, String content) {
        requireAllowed(content);
        ChatMessage message = messages.save(ChatMessage.send(room, sender, key, content, clock.instant()));
        if (aiEvents != null) aiEvents.chat(sender, room.getId(), message.getId());
        return published(message, List.of(), room);
    }

    // 사진 행을 잠근 채 메시지를 저장하고 연결함. 한 장이라도 쓸 수 없으면 전체가 실패하고 메시지와 연결이 함께 롤백됨.
    // 설명 글이 있어도 AI 사용자 이벤트는 보내지 않음.
    private ChatMessageResponse sendImages(User sender, ChatRoom room, String key, Draft draft) {
        if (draft.content() != null) requireAllowed(draft.content());
        // 여러 행을 잠그므로 교착이 생기지 않게 id 오름차순으로 잠금.
        Map<Long, ChatImage> locked = new HashMap<>();
        for (Long imageId : draft.imageIds().stream().sorted().toList()) {
            locked.put(imageId, images.findForUpdate(imageId).orElseThrow(ChatCommandService::imageUnavailable));
        }
        // 잠금을 기다리는 동안 취소된 사진이 통과하지 않도록 잠금 뒤의 시각으로 판정함.
        Instant now = clock.instant();
        if (!locked.values().stream().allMatch(image -> image.isAttachableBy(sender.getId(), room.getId(), now))) {
            throw imageUnavailable();
        }
        ChatMessage message = messages.save(
                ChatMessage.sendImage(room, sender, key, draft.content(), draft.imageIds().size(), now));
        List<ChatImage> attached = new ArrayList<>();
        for (int order = 0; order < draft.imageIds().size(); order++) {
            ChatImage image = locked.get(draft.imageIds().get(order));
            image.attach(message.getId(), order);
            attached.add(image);
        }
        return published(message, attached, room);
    }

    private void requireAllowed(String text) {
        if (bannedWords.containsBannedWord(text)) throw new BusinessException(ChatErrorCode.CHAT_CONTENT_BANNED);
    }

    private ChatMessageResponse published(ChatMessage message, List<ChatImage> attached, ChatRoom room) {
        // 발신만으로 이전 메시지를 읽었다고 간주하지 않음. 화면에 표시된 지점은 read API로 명시함.
        events.publishEvent(new ChatRoomChanged(room.getId()));
        events.publishEvent(new ChatMessageSent(message.getId()));
        return ChatMessageResponse.of(message, attached, List.of(), query.snapshot(room));
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

    private static BusinessException imageUnavailable() {
        return new BusinessException(ChatErrorCode.CHAT_IMAGE_UNAVAILABLE);
    }
}
