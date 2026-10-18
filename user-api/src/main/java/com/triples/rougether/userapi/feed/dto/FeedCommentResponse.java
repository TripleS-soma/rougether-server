package com.triples.rougether.userapi.feed.dto;

import com.triples.rougether.domain.feed.entity.FeedComment;
import java.time.Instant;

public record FeedCommentResponse(Long commentId, Long postId, FeedAuthorResponse author,
                                  String content, boolean mine, Instant createdAt,
                                  Long parentCommentId, Long replyToCommentId, boolean deleted, long replyCount) {
    public static FeedCommentResponse of(FeedComment comment, Long viewer) {
        return of(comment, viewer, 0);
    }
    public static FeedCommentResponse of(FeedComment comment, Long viewer, long replyCount) {
        boolean deleted = comment.getDeletedAt() != null || comment.getAuthor().isDeleted();
        return new FeedCommentResponse(comment.getId(), comment.getPost().getId(),
                deleted ? new FeedAuthorResponse(null, null, null) : FeedAuthorResponse.of(comment.getAuthor()),
                deleted ? "" : comment.getContent(), !deleted && comment.getAuthor().getId().equals(viewer),
                comment.getCreatedAt(), comment.getParentCommentId(), comment.getReplyToCommentId(), deleted, replyCount);
    }
}
