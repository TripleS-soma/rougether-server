package com.triples.rougether.userapi.feed.dto;

import com.triples.rougether.domain.feed.entity.FeedComment;
import java.time.Instant;

public record FeedCommentResponse(Long commentId, Long postId, FeedAuthorResponse author,
                                  String content, boolean mine, Instant createdAt,
                                  Long parentCommentId, Long replyToCommentId, boolean deleted, long replyCount,
                                  @io.swagger.v3.oas.annotations.media.Schema(description = "요청자가 이 댓글을 지울 수 있는지. "
                                          + "본인 댓글이거나 집 글에서 요청자가 그 집의 방장이면 true")
                                  boolean deletable) {
    public static FeedCommentResponse of(FeedComment comment, Long viewer) {
        return of(comment, viewer, 0);
    }
    public static FeedCommentResponse of(FeedComment comment, Long viewer, long replyCount) {
        return of(comment, viewer, replyCount, false);
    }
    // moderator 는 요청자가 이 댓글이 달린 집 글의 방장인지임. 공개 글이면 항상 false.
    public static FeedCommentResponse of(FeedComment comment, Long viewer, long replyCount, boolean moderator) {
        boolean deleted = comment.getDeletedAt() != null || comment.getAuthor().isDeleted();
        boolean mine = !deleted && comment.getAuthor().getId().equals(viewer);
        return new FeedCommentResponse(comment.getId(), comment.getPost().getId(),
                deleted ? new FeedAuthorResponse(null, null, null) : FeedAuthorResponse.of(comment.getAuthor()),
                deleted ? "" : comment.getContent(), mine,
                comment.getCreatedAt(), comment.getParentCommentId(), comment.getReplyToCommentId(), deleted, replyCount,
                !deleted && (mine || moderator));
    }
}
