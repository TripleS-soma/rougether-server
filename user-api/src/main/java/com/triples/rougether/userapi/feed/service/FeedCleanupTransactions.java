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
    // 집이 해체되면 그 집 글은 이미 아무도 열 수 없음(#528). 여기서는 남은 본문을 지워 삭제된 글로 만듦(#531).
    // 이어지는 withdrawnContent 와 사진 정리가 삭제된 글의 댓글·좋아요·사진을 치움.
    // READ_COMMITTED 로 조건에 맞지 않는 글의 잠금을 바로 풀어 글 등록·좋아요·댓글이 정리 작업을 기다리지 않게 함.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int dissolvedHousePosts() {
        return posts.eraseDissolvedHouses(clock.instant());
    }
    @Transactional
    public void withdrawnContent() {
        posts.eraseWithdrawn(clock.instant());
        comments.eraseWithdrawn(clock.instant());
        likes.deleteWithdrawn();
    }
}
