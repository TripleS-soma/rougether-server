package com.triples.rougether.userapi.feed.dto;

import com.triples.rougether.domain.feed.entity.FeedBoardType;
import java.time.Instant;
import java.util.List;

public record FeedPostResponse(Long postId, FeedAuthorResponse author, String content,
                               List<FeedImageResponse> images, long likeCount, long commentCount,
                               boolean likedByMe, boolean mine, Instant createdAt, Instant updatedAt, FeedBoardType boardType) { }
