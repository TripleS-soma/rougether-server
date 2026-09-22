package com.triples.rougether.userapi.feed.dto;

import com.triples.rougether.domain.member.entity.User;

public record FeedAuthorResponse(Long userId, String nickname, String profileImageKey) {
    public static FeedAuthorResponse of(User user) {
        return new FeedAuthorResponse(user.getId(), user.getNickname(), user.getProfileImageKey());
    }
}
