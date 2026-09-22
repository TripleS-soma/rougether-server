package com.triples.rougether.userapi.feed.dto;

import com.triples.rougether.domain.feed.entity.FeedComment;
import java.time.Instant;

public record FeedCommentResponse(Long commentId, Long postId, FeedAuthorResponse author,
                                  String content, boolean mine, Instant createdAt) {
    public static FeedCommentResponse of(FeedComment comment, Long viewer) {
        return new FeedCommentResponse(comment.getId(), comment.getPost().getId(),
                FeedAuthorResponse.of(comment.getAuthor()), comment.getContent(),
                comment.getAuthor().getId().equals(viewer), comment.getCreatedAt());
    }
}
