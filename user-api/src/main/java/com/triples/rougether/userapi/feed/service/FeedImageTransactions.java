package com.triples.rougether.userapi.feed.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.feed.entity.FeedImage;
import com.triples.rougether.domain.feed.repository.FeedImageRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.userapi.feed.dto.FeedImageResponse;
import static com.triples.rougether.userapi.feed.error.FeedErrorCode.*;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class FeedImageTransactions {
    private final FeedAccess access;
    private final FeedImageRepository images;
    private final Clock clock;
    public record Reservation(Long id, String key) { }

    public Reservation reserve(Long userId, int width, int height) {
        User user = access.lockActive(userId);
        if (images.countByOwnerIdAndPostIsNull(userId) >= 30) throw new BusinessException(FEED_UPLOAD_LIMIT);
        FeedImage image = images.save(FeedImage.reserve(user, "private/feed/" + UUID.randomUUID() + ".jpg", width, height,
                clock.instant().plus(Duration.ofHours(24))));
        return new Reservation(image.getId(), image.getStorageKey());
    }
    public FeedImageResponse complete(Long userId, Long imageId) {
        access.lockActive(userId);
        FeedImage image = images.findForUpdate(imageId).orElseThrow(() -> new BusinessException(FEED_IMAGE_UNAVAILABLE));
        if (!image.getOwner().getId().equals(userId) || !image.getExpiresAt().isAfter(clock.instant()))
            throw new BusinessException(FEED_IMAGE_UNAVAILABLE);
        image.complete();
        return FeedImageResponse.of(image);
    }
    @Transactional(readOnly = true)
    public String readableKey(Long viewer, Long imageId) {
        access.active(viewer);
        FeedImage image = images.findById(imageId).orElseThrow(() -> new BusinessException(FEED_IMAGE_NOT_FOUND));
        if (!image.isReady() || image.getOwner().isDeleted()) throw new BusinessException(FEED_IMAGE_NOT_FOUND);
        if (image.getPost() == null) {
            if (!image.getOwner().getId().equals(viewer) || !image.getExpiresAt().isAfter(clock.instant()))
                throw new BusinessException(FEED_IMAGE_NOT_FOUND);
        } else if (image.getPost().getDeletedAt() != null) throw new BusinessException(FEED_IMAGE_NOT_FOUND);
        return image.getStorageKey();
    }
    public void cancel(Long userId, Long imageId) {
        access.lockActive(userId);
        FeedImage image = images.findForUpdate(imageId).orElseThrow(() -> new BusinessException(FEED_IMAGE_NOT_FOUND));
        if (!image.getOwner().getId().equals(userId)) throw new BusinessException(FEED_FORBIDDEN);
        if (!image.isReady() || image.getPost() != null) throw new BusinessException(FEED_IMAGE_UNAVAILABLE);
        image.expire(clock.instant());
    }
}
