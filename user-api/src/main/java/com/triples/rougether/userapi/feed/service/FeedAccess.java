package com.triples.rougether.userapi.feed.service;

import com.triples.rougether.common.error.AuthErrorCode;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.userapi.feed.error.FeedErrorCode;
import java.util.stream.Stream;
import com.triples.rougether.domain.member.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FeedAccess {
    private final UserRepository users;

    public User active(Long userId) {
        return requireActive(users.findById(userId).orElse(null));
    }
    // 쓰기는 회원 → 게시물 → 이미지 순서로 잠금. 탈퇴의 회원 잠금과 직렬화함.
    public User lockActive(Long userId) {
        return requireActive(users.findByIdForUpdate(userId).orElse(null));
    }

    public User lockCommentParticipants(Long actorId, Long authorId) {
        User actor = null;
        // 알림 FK가 수신자 회원 행도 참조함. 서로의 글에 댓글을 달아도 회원 잠금이 교차하지 않도록 정렬함.
        for (Long id : Stream.of(actorId, authorId).distinct().sorted().toList()) {
            User locked = users.findByIdForUpdate(id).orElse(null);
            if (id.equals(actorId)) actor = requireActive(locked);
            else if (locked == null || locked.isDeleted() || locked.isBot())
                throw new BusinessException(FeedErrorCode.FEED_POST_NOT_FOUND);
        }
        return actor;
    }
    private User requireActive(User user) {
        if (user == null || user.isDeleted() || user.isBot()) throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
        return user;
    }
}
