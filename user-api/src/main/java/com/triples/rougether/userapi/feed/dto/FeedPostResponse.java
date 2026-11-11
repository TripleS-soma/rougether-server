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
                               FeedMinigameRecordResponse minigameRecord,
                               @Schema(description = "글이 속한 집 ID. 공개 글이면 null", nullable = true)
                               Long houseId,
                               @Schema(description = "요청자가 이 글을 지울 수 있는지. 본인 글이거나 집 글에서 요청자가 그 집의 방장이면 true")
                               boolean deletable) { }
