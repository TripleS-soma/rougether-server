package com.triples.rougether.adminapi.moderation.dto;

// deleted: 이번 요청으로 삭제됐는지(이미 삭제된 글이면 false). resolvedReports: 함께 닫힌 대기 신고 수.
public record AdminFeedPostDeleteResponse(Long postId, boolean deleted, int resolvedReports) {
}
