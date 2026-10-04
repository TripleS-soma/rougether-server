package com.triples.rougether.userapi.feed.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.feed.entity.*;
import com.triples.rougether.domain.feed.repository.*;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.moderation.repository.UserBlockRepository;
import com.triples.rougether.userapi.feed.dto.*;
import static com.triples.rougether.userapi.feed.error.FeedErrorCode.*;
import com.triples.rougether.userapi.global.text.BannedWordChecker;
import com.triples.rougether.userapi.notification.service.NotificationService;
import com.triples.rougether.userapi.notification.message.NotificationMessages;
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
    private final UserBlockRepository blocks;
    private final BannedWordChecker bannedWords;
    private final NotificationService notifications;
    private final FeedRoutineLinks routineLinks;
    private final Clock clock;

    public Long create(Long userId, FeedCreateRequest request) {
        User user = access.lockActive(userId);
        FeedBoardType boardType = request.boardType() == null ? FeedBoardType.VERIFICATION : request.boardType();
        List<Long> imageIds = request.imageIds() == null ? List.of() : request.imageIds();
        if (request.clientPostId() == null || imageIds.size() > 10
                || (boardType == FeedBoardType.VERIFICATION && imageIds.isEmpty())
                || imageIds.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(imageIds).size() != imageIds.size()) throw new BusinessException(FEED_INPUT_INVALID);
        FeedRoutineCompletionRequest completion = request.routineCompletion();
        if (boardType == FeedBoardType.FREE && completion != null) throw new BusinessException(FEED_INPUT_INVALID);
        String content = text(request.content(), 2000, !imageIds.isEmpty());
        // 기존 게시물의 재시도 hash를 유지하고 게시판 종류는 별도로 비교함. 루틴 연결은 있을 때만 hash에 덧붙임.
        String hashed = content + "\u0000" + imageIds;
        if (completion != null) hashed += "\u0000routine:" + completion.routineId() + ":" + completion.date();
        String requestHash = hash(hashed);
        FeedPost previous = posts.findByAuthorIdAndClientPostId(userId, request.clientPostId().toString()).orElse(null);
        if (previous != null) {
            if (previous.getBoardType() != boardType || !previous.getRequestHash().equals(requestHash) || previous.getDeletedAt() != null)
                throw new BusinessException(FEED_REQUEST_CONFLICT);
            return previous.getId();
        }
        // 재시도 판정 뒤에 검사함 — 연결 규칙 도입 전에 등록된 인증글의 재시도도 원래 결과를 돌려받음
        if (boardType == FeedBoardType.VERIFICATION && completion == null)
            throw new BusinessException(FEED_ROUTINE_COMPLETION_REQUIRED);
        FeedRoutineLinks.Link link = completion == null ? null : routineLinks.verify(userId, completion);
        Map<Long, FeedImage> selected = new HashMap<>();
        // 업로드 ID 입력 순서와 무관하게 잠금 순서를 고정함. 표시 순서는 요청 순서를 유지함.
        for (Long id : imageIds.stream().sorted().toList()) {
            FeedImage image = images.findForUpdate(id).orElseThrow(() -> new BusinessException(FEED_IMAGE_UNAVAILABLE));
            if (!image.getOwner().getId().equals(userId) || !image.isReady() || image.getPost() != null
                    || !image.getExpiresAt().isAfter(clock.instant())) throw new BusinessException(FEED_IMAGE_UNAVAILABLE);
            selected.put(id, image);
        }
        FeedPost post = FeedPost.create(user, request.clientPostId().toString(), requestHash, content, boardType);
        if (link != null) post.linkRoutine(link.routineId(), link.date(), link.title());
        post = posts.save(post);
        for (int i = 0; i < imageIds.size(); i++) selected.get(imageIds.get(i)).attach(post, i);
        return post.getId();
    }
    public void update(Long userId, Long postId, String content) {
        update(userId, postId, new FeedUpdateRequest(content));
    }
    // 생략한 필드는 유지함. { content }만 보내는 구버전 요청은 기존 본문 수정과 같게 동작함.
    public void update(Long userId, Long postId, FeedUpdateRequest request) {
        access.lockActive(userId);
        FeedPost post = lockVisible(postId);
        requireOwner(post.getAuthor().getId(), userId);
        FeedRoutineCompletionRequest completion = request.routineCompletion();
        if (request.content() == null && request.boardType() == null && completion == null)
            throw new BusinessException(FEED_INPUT_INVALID);
        FeedBoardType target = request.boardType() == null ? post.getBoardType() : request.boardType();
        if (target == FeedBoardType.FREE && completion != null) throw new BusinessException(FEED_INPUT_INVALID);
        boolean hasImages = images.existsByPostId(postId);
        boolean switching = target != post.getBoardType();
        // 사진은 수정할 수 없으므로 사진 없는 글은 인증게시판으로 옮길 수 없음
        if (switching && target == FeedBoardType.VERIFICATION && !hasImages) throw new BusinessException(FEED_INPUT_INVALID);
        boolean emptyAllowed = target == FeedBoardType.VERIFICATION || hasImages;
        String content = request.content() == null ? null : text(request.content(), 2000, emptyAllowed);
        // 본문을 생략한 채 사진 없는 자유글이 되면 비어 있지 않은 기존 본문이 필요함
        if (content == null && !emptyAllowed && post.getContent().isBlank()) throw new BusinessException(FEED_INPUT_INVALID);
        if (switching && target == FeedBoardType.VERIFICATION && completion == null)
            throw new BusinessException(FEED_ROUTINE_COMPLETION_REQUIRED);
        FeedRoutineLinks.Link link = completion == null ? null : routineLinks.verify(userId, completion);
        if (content != null) post.updateContent(content);
        if (switching) post.changeBoard(target);
        if (target == FeedBoardType.FREE) post.clearRoutine();
        else if (link != null) post.linkRoutine(link.routineId(), link.date(), link.title());
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
        requireNotBlocked(userId, post.getAuthor().getId());
        Optional<FeedLike> previous = likes.findByPostIdAndUserId(postId, userId);
        if (liked && previous.isEmpty()) likes.save(new FeedLike(post, user));
        if (!liked) previous.ifPresent(likes::delete);
    }
    public FeedCommentResponse comment(Long userId, Long postId, FeedCommentRequest request) {
        Long authorId = posts.findAuthorId(postId).orElseThrow(() -> new BusinessException(FEED_POST_NOT_FOUND));
        User user = access.lockCommentParticipants(userId, authorId);
        FeedPost post = lockVisible(postId);
        requireNotBlocked(userId, authorId);
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
        // 댓글과 알림 내역을 함께 커밋하고 push만 기존 AFTER_COMMIT 경로로 발송함. 재시도·본인 댓글은 제외함.
        // 게시물 작성자가 댓글 작성자를 차단했으면 알림을 만들지 않음(#399).
        if (!authorId.equals(userId) && !blocks.existsByBlockerUserIdAndBlockedUserId(authorId, userId))
            notifications.send(authorId, NotificationMessages.feedComment(), postId);
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
    // 차단한 작성자의 글은 조회와 같게 404로 막음(#399).
    private void requireNotBlocked(Long viewer, Long authorId) {
        if (blocks.existsByBlockerUserIdAndBlockedUserId(viewer, authorId)) throw new BusinessException(FEED_POST_NOT_FOUND);
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
