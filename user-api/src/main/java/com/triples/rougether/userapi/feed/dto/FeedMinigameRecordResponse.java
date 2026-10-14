package com.triples.rougether.userapi.feed.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// 게시 시점에 서버가 고정한 기록 카드 값. 이후 랭킹이 바뀌어도 그대로임.
@Schema(description = "글에 연결된 미니게임 기록 카드. 게시 시점 스냅샷이며 바뀌지 않음")
public record FeedMinigameRecordResponse(
        @Schema(description = "게임 코드", example = "cat-tower") String gameCode,
        @Schema(description = "연결한 판의 서버 검증 점수", example = "42") int score,
        @Schema(description = "게시 시점 작성자의 이 게임 전체 순위(최고점 기준, 동점 공동 순위). 순위가 없으면 null",
                example = "7", nullable = true) Integer rank,
        @Schema(description = "연결한 판의 점수가 게시 시점 작성자의 이 게임 최고점과 같은지", example = "true") boolean personalBest,
        @Schema(description = "연결한 판을 끝낸 시각(ISO-8601 UTC)", example = "2026-10-14T03:00:00Z") Instant achievedAt) { }
