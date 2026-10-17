package com.triples.rougether.userapi.minigame.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record MinigameFinishResponse(
        @Schema(description = "플레이 세션 ID") String runId,
        @Schema(description = "서버가 재생해 계산한 이번 판 점수") int score,
        @Schema(description = "이번 판까지 반영한 이 게임의 내 최고점") int bestScore,
        @Schema(description = "이번 판이 내 최고점을 갱신했는지") boolean personalBest,
        @Schema(description = "제출 시점 내 최고점 기준 전체 순위(동점 공동 순위)") long rank,
        @Schema(description = "이 게임을 끝낸 판 수. 이번 판 포함이며 같은 제출의 재요청에도 같은 값임", example = "10",
                requiredMode = Schema.RequiredMode.REQUIRED) int playCount) {}
