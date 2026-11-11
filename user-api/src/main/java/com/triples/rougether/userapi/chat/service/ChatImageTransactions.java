package com.triples.rougether.userapi.chat.service;

import static com.triples.rougether.userapi.chat.error.ChatErrorCode.CHAT_IMAGE_LIMIT;
import static com.triples.rougether.userapi.chat.error.ChatErrorCode.CHAT_IMAGE_NOT_FOUND;
import static com.triples.rougether.userapi.chat.error.ChatErrorCode.CHAT_IMAGE_UNAVAILABLE;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.chat.entity.ChatImage;
import com.triples.rougether.domain.chat.repository.ChatImageRepository;
import com.triples.rougether.domain.chat.repository.ChatMessageRepository;
import com.triples.rougether.domain.chat.repository.ChatRoomRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.userapi.chat.dto.ChatImageResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ChatImageTransactions {
    private static final int MAX_UNSENT = 30;
    private static final int MAX_DAILY = 50;
    private static final Duration UNSENT_TTL = Duration.ofHours(24);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ChatAccessPolicy access;
    private final ChatImageRepository images;
    private final ChatRoomRepository rooms;
    private final ChatMessageRepository messages;
    private final Clock clock;

    public record Reservation(Long id, String key) { }

    // 사진 디코딩 전에 권한·한도를 먼저 거름. 실제 판정은 reserve의 잠금 안에서 다시 함.
    public void checkUploadable(Long userId, Long roomId) {
        access.lockWriter(roomId, userId);
        requireWithinLimits(userId, clock.instant());
    }

    // 사용자 행 잠금(lockWriter) 안에서 세어 동시 업로드가 한도를 넘지 못하게 함.
    public Reservation reserve(Long userId, Long roomId, int width, int height) {
        User user = access.lockWriter(roomId, userId);
        Instant now = clock.instant();
        requireWithinLimits(userId, now);
        ChatImage image = images.save(ChatImage.reserve(roomId, user, "private/chat/" + UUID.randomUUID() + ".jpg",
                width, height, now, now.plus(UNSENT_TTL)));
        return new Reservation(image.getId(), image.getStorageKey());
    }

    public ChatImageResponse complete(Long userId, Long imageId) {
        ChatImage image = images.findForUpdate(imageId).orElseThrow(ChatImageTransactions::unavailable);
        if (!image.getOwner().getId().equals(userId) || !image.getExpiresAt().isAfter(clock.instant())) {
            throw unavailable();
        }
        image.complete();
        return ChatImageResponse.of(image);
    }

    public void abandon(Long imageId) {
        images.findForUpdate(imageId).ifPresent(image -> image.expire(clock.instant()));
    }

    // 권한이 없으면 사진의 존재도 드러내지 않도록 전부 404로 응답함.
    @Transactional(readOnly = true)
    public String readableKey(Long viewerId, Long imageId) {
        ChatImage image = images.findById(imageId).orElseThrow(ChatImageTransactions::notFound);
        if (!image.isReady() || image.getOwner().isDeleted()) throw notFound();
        boolean readable = image.getMessageId() == null
                ? image.getOwner().getId().equals(viewerId) && image.getExpiresAt().isAfter(clock.instant())
                : messages.findById(image.getMessageId()).filter(message -> !message.isDeleted()).isPresent()
                        && rooms.findById(image.getRoomId()).filter(room -> access.canRead(room, viewerId)).isPresent();
        if (!readable) throw notFound();
        return image.getStorageKey();
    }

    public void cancel(Long userId, Long imageId) {
        ChatImage image = images.findForUpdate(imageId).orElseThrow(ChatImageTransactions::notFound);
        if (!image.getOwner().getId().equals(userId)) throw notFound();
        if (!image.isReady() || image.getMessageId() != null) throw unavailable();
        image.expire(clock.instant());
    }

    static Instant startOfKstDay(Instant now) {
        return now.atZone(KST).toLocalDate().atStartOfDay(KST).toInstant();
    }

    private void requireWithinLimits(Long userId, Instant now) {
        if (images.countByOwnerIdAndMessageIdIsNullAndExpiresAtAfter(userId, now) >= MAX_UNSENT
                || images.countByOwnerIdAndCreatedAtGreaterThanEqual(userId, startOfKstDay(now)) >= MAX_DAILY) {
            throw new BusinessException(CHAT_IMAGE_LIMIT);
        }
    }

    private static BusinessException notFound() { return new BusinessException(CHAT_IMAGE_NOT_FOUND); }

    private static BusinessException unavailable() { return new BusinessException(CHAT_IMAGE_UNAVAILABLE); }
}
