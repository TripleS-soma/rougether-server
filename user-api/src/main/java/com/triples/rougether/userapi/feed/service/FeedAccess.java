package com.triples.rougether.userapi.feed.service;

import com.triples.rougether.common.error.AuthErrorCode;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.feed.entity.FeedComment;
import com.triples.rougether.domain.feed.repository.FeedCommentRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.moderation.repository.UserBlockRepository;
import com.triples.rougether.userapi.feed.error.FeedErrorCode;
import java.util.stream.Stream;
import com.triples.rougether.domain.member.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FeedAccess {
    private final UserRepository users;
    private final FeedCommentRepository comments;
    private final UserBlockRepository blocks;

    public User active(Long userId) {
        return requireActive(users.findById(userId).orElse(null));
    }
    // 쓰기는 회원 → 게시물 → 이미지 순서로 잠금. 탈퇴의 회원 잠금과 직렬화함.
    public User lockActive(Long userId) {
        return requireActive(users.findByIdForUpdate(userId).orElse(null));
    }

    public User lockCommentParticipants(Long actorId, Long authorId, Long replyAuthorId) {
        User actor = null;
        // 게시물·답글 수신자의 알림 FK도 회원을 참조하므로 모든 참여자를 정렬해 잠금.
        // 답글 대상의 탈퇴 여부는 재시도 판정 뒤에 검사해 이미 성공한 요청의 결과를 유지함.
        for (Long id : Stream.of(actorId, authorId, replyAuthorId)
                .filter(java.util.Objects::nonNull).distinct().sorted().toList()) {
            User locked = users.findByIdForUpdate(id).orElse(null);
            if (id.equals(actorId)) actor = requireActive(locked);
            else if (id.equals(authorId) && (locked == null || locked.isDeleted() || locked.isBot()))
                throw new BusinessException(FeedErrorCode.FEED_POST_NOT_FOUND);
        }
        return actor;
    }
    // 삭제·탈퇴한 원댓글 아래의 살아 있는 답글은 계속 접근할 수 있음.
    public void requireThreadVisible(Long viewer, Long postId, Long parentId) {
        FeedComment parent = comments.findByIdAndPostId(parentId, postId)
                .orElseThrow(() -> new BusinessException(FeedErrorCode.FEED_COMMENT_NOT_FOUND));
        if (parent.getParentCommentId() != null
                || blocks.existsByBlockerUserIdAndBlockedUserId(viewer, parent.getAuthor().getId()))
            throw new BusinessException(FeedErrorCode.FEED_COMMENT_NOT_FOUND);
    }

    private User requireActive(User user) {
        if (user == null || user.isDeleted() || user.isBot()) throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
        return user;
    }
}
