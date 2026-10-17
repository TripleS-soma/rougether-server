package com.triples.rougether.userapi.feed.dto;

import com.triples.rougether.domain.feed.entity.FeedBoardType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record FeedPostResponse(Long postId, FeedAuthorResponse author, String content,
                               List<FeedImageResponse> images, long likeCount, long commentCount,
                               boolean likedByMe, boolean mine, Instant createdAt, Instant updatedAt, FeedBoardType boardType,
                               FeedRoutineResponse routine,
                               @Schema(description = "연결된 미니게임 기록 카드. 없으면 null", nullable = true)
                               FeedMinigameRecordResponse minigameRecord) { }
