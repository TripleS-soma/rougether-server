package com.triples.rougether.userapi.chat.service;

import com.triples.rougether.domain.chat.entity.ChatImage;
import com.triples.rougether.domain.chat.entity.ChatMessage;
import com.triples.rougether.domain.chat.entity.ChatRoom;
import com.triples.rougether.domain.chat.repository.ChatImageRepository;
import com.triples.rougether.domain.chat.repository.ChatMessageRepository;
import com.triples.rougether.domain.chat.repository.ChatRoomRepository;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.repository.HouseRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatCleanupTransactions {
    private final ChatImageRepository images;
    private final ChatMessageRepository messages;
    private final ChatRoomRepository rooms;
    private final HouseRepository houses;
    private final ChatImageStorage storage;
    private final Clock clock;

    // 전송 연결과 같은 행 잠금 안에서 판정·삭제함. S3 실패 시 행을 남겨 다음 실행에서 재시도함.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void image(Long id) {
        var image = images.findForUpdate(id).orElse(null);
        if (image == null || !removable(image)) return;
        storage.delete(image.getStorageKey());
        images.delete(image);
    }

    private boolean removable(ChatImage image) {
        var now = clock.instant();
        // 오늘 올린 미전송 행은 하루 업로드 한도 집계에 필요하므로 다음 날 지움.
        boolean expiredUnsent = image.getMessageId() == null && !image.getExpiresAt().isAfter(now)
                && image.getCreatedAt().isBefore(ChatImageTransactions.startOfKstDay(now));
        // 저장 중인 행은 S3 객체가 뒤늦게 남지 않도록 만료 후 정리에 맡김.
        return expiredUnsent || (image.isReady()
                && (image.getOwner().isDeleted() || messageDeleted(image) || houseDeleted(image)));
    }

    private boolean messageDeleted(ChatImage image) {
        return image.getMessageId() != null
                && messages.findById(image.getMessageId()).map(ChatMessage::isDeleted).orElse(false);
    }

    private boolean houseDeleted(ChatImage image) {
        return rooms.findById(image.getRoomId()).map(ChatRoom::getHouseId).flatMap(houses::findById)
                .map(House::isDeleted).orElse(false);
    }
}
