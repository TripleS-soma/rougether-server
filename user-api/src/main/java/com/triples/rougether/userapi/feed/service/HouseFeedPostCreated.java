package com.triples.rougether.userapi.feed.service;

// 집 글이 새로 등록됨(#530). 커밋 후 새 글 푸시가 받음. clientPostId 재시도로 기존 글을 돌려줄 때는 발행하지 않음.
public record HouseFeedPostCreated(Long postId) {}
