package com.triples.rougether.userapi.feed.service;

import com.triples.rougether.common.error.AuthErrorCode;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
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
    // 모든 쓰기는 회원 → 게시물 → 이미지 순서로 잠금. 탈퇴의 회원 잠금과 직렬화함.
    public User lockActive(Long userId) {
        return requireActive(users.findByIdForUpdate(userId).orElse(null));
    }
    private User requireActive(User user) {
        if (user == null || user.isDeleted() || user.isBot()) throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
        return user;
    }
}
