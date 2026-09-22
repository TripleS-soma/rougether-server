package com.triples.rougether.userapi.feed.service;

import com.triples.rougether.domain.feed.repository.*;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class FeedCleanupTransactions {
    private final FeedImageRepository images;
    private final FeedPostRepository posts;
    private final FeedCommentRepository comments;
    private final FeedLikeRepository likes;
    private final FeedImageStorage storage;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void image(Long id) {
        var image = images.findForUpdate(id).orElse(null);
        if (image == null) return;
        boolean expired = image.getPost() == null && !image.getExpiresAt().isAfter(clock.instant());
        boolean deleted = image.isReady() && (image.getOwner().isDeleted()
                || (image.getPost() != null && image.getPost().getDeletedAt() != null));
        if (!expired && !deleted) return;
        // 첨부와 동일한 이미지 잠금 안에서 판정·삭제함. S3 실패 시 row를 남겨 다음 실행에서 재시도함.
        storage.delete(image.getStorageKey());
        images.delete(image);
    }
    @Transactional
    public void withdrawnContent() {
        posts.eraseWithdrawn(clock.instant());
        comments.eraseWithdrawn(clock.instant());
        likes.deleteWithdrawn();
    }
}
