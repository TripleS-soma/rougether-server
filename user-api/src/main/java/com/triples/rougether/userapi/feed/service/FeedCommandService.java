package com.triples.rougether.userapi.feed.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.feed.entity.*;
import com.triples.rougether.domain.feed.repository.*;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.userapi.feed.dto.*;
import static com.triples.rougether.userapi.feed.error.FeedErrorCode.*;
import com.triples.rougether.userapi.global.text.BannedWordChecker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class FeedCommandService {
    private final FeedAccess access;
    private final FeedPostRepository posts;
    private final FeedImageRepository images;
    private final FeedLikeRepository likes;
    private final FeedCommentRepository comments;
    private final BannedWordChecker bannedWords;
    private final Clock clock;

    public Long create(Long userId, FeedCreateRequest request) {
        User user = access.lockActive(userId);
        List<Long> imageIds = request.imageIds();
        if (request.clientPostId() == null || imageIds == null || imageIds.isEmpty() || imageIds.size() > 10
                || imageIds.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(imageIds).size() != imageIds.size()) throw new BusinessException(FEED_INPUT_INVALID);
        String content = text(request.content(), 2000, true);
        String requestHash = hash(content + "\u0000" + imageIds);
        FeedPost previous = posts.findByAuthorIdAndClientPostId(userId, request.clientPostId().toString()).orElse(null);
        if (previous != null) {
            if (!previous.getRequestHash().equals(requestHash) || previous.getDeletedAt() != null)
                throw new BusinessException(FEED_REQUEST_CONFLICT);
            return previous.getId();
        }
        Map<Long, FeedImage> selected = new HashMap<>();
        // 업로드 ID 입력 순서와 무관하게 잠금 순서를 고정함. 표시 순서는 요청 순서를 유지함.
        for (Long id : imageIds.stream().sorted().toList()) {
            FeedImage image = images.findForUpdate(id).orElseThrow(() -> new BusinessException(FEED_IMAGE_UNAVAILABLE));
            if (!image.getOwner().getId().equals(userId) || !image.isReady() || image.getPost() != null
                    || !image.getExpiresAt().isAfter(clock.instant())) throw new BusinessException(FEED_IMAGE_UNAVAILABLE);
            selected.put(id, image);
        }
        FeedPost post = posts.save(FeedPost.create(user, request.clientPostId().toString(), requestHash, content));
        for (int i = 0; i < imageIds.size(); i++) selected.get(imageIds.get(i)).attach(post, i);
        return post.getId();
    }
    public void update(Long userId, Long postId, String content) {
        access.lockActive(userId);
        FeedPost post = lockVisible(postId);
        requireOwner(post.getAuthor().getId(), userId);
        if (content == null) throw new BusinessException(FEED_INPUT_INVALID);
        post.updateContent(text(content, 2000, true));
    }
    public void delete(Long userId, Long postId) {
        access.lockActive(userId);
        FeedPost post = posts.findForUpdate(postId).orElseThrow(() -> new BusinessException(FEED_POST_NOT_FOUND));
        requireOwner(post.getAuthor().getId(), userId);
        post.delete(clock.instant());
        // 게시물 tombstone과 원래 요청 hash는 재시도의 재생성을 막기 위해 유지함.
        comments.deleteForPost(postId);
        likes.deleteForPost(postId);
    }
    public void like(Long userId, Long postId, boolean liked) {
        User user = access.lockActive(userId);
        FeedPost post = lockVisible(postId);
        Optional<FeedLike> previous = likes.findByPostIdAndUserId(postId, userId);
        if (liked && previous.isEmpty()) likes.save(new FeedLike(post, user));
        if (!liked) previous.ifPresent(likes::delete);
    }
    public FeedCommentResponse comment(Long userId, Long postId, FeedCommentRequest request) {
        User user = access.lockActive(userId);
        FeedPost post = lockVisible(postId);
        if (request.clientCommentId() == null) throw new BusinessException(FEED_INPUT_INVALID);
        String content = text(request.content(), 500, false);
        String requestHash = hash(content);
        FeedComment previous = comments.findByPostIdAndAuthorIdAndClientCommentId(
                postId, userId, request.clientCommentId().toString()).orElse(null);
        if (previous != null) {
            if (!previous.getRequestHash().equals(requestHash) || previous.getDeletedAt() != null)
                throw new BusinessException(FEED_REQUEST_CONFLICT);
            return FeedCommentResponse.of(previous, userId);
        }
        FeedComment comment = comments.save(FeedComment.create(post, user, request.clientCommentId().toString(), requestHash, content));
        return FeedCommentResponse.of(comment, userId);
    }
    public void deleteComment(Long userId, Long postId, Long commentId) {
        access.lockActive(userId);
        lockVisible(postId);
        FeedComment comment = comments.findByIdAndPostId(commentId, postId)
                .orElseThrow(() -> new BusinessException(FEED_COMMENT_NOT_FOUND));
        requireOwner(comment.getAuthor().getId(), userId);
        comment.delete(clock.instant());
    }
    private FeedPost lockVisible(Long postId) {
        FeedPost post = posts.findForUpdate(postId).orElseThrow(() -> new BusinessException(FEED_POST_NOT_FOUND));
        if (post.getDeletedAt() != null || post.getAuthor().isDeleted()) throw new BusinessException(FEED_POST_NOT_FOUND);
        return post;
    }
    private void requireOwner(Long author, Long user) {
        if (!author.equals(user)) throw new BusinessException(FEED_FORBIDDEN);
    }
    private String text(String content, int limit, boolean emptyAllowed) {
        String value = content == null ? "" : content.strip();
        if (value.length() > limit || (!emptyAllowed && value.isEmpty())) throw new BusinessException(FEED_INPUT_INVALID);
        if (bannedWords.containsBannedWord(value)) throw new BusinessException(FEED_CONTENT_BANNED);
        return value;
    }
    private String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
