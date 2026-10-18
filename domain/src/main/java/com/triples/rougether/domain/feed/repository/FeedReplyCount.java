package com.triples.rougether.domain.feed.repository;

public interface FeedReplyCount {
    Long getParentCommentId();
    long getTotal();
}
