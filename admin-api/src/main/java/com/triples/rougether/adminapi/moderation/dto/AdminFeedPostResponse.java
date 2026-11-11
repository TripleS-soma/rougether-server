package com.triples.rougether.adminapi.moderation.dto;

import java.time.Instant;
import java.util.List;

// 어드민 피드 관리 목록의 한 행. 삭제된 글은 본문이 비어 있고 deletedAt 이 채워짐.
public record AdminFeedPostResponse(Long postId, String boardType, String text, Long authorUserId,
                                    String authorNickname, boolean authorWithdrawn, List<Long> imageIds,
                                    Instant createdAt, Instant deletedAt) {
}
