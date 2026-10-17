package com.triples.rougether.userapi.feed.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 자유글에 연결할 본인의 완료된 미니게임 판. 점수·순위는 서버가 판 기록에서 직접 읽음.
@Schema(description = "자유글에 연결할 미니게임 기록. 점수·순위는 보내지 않으며 서버가 판 기록으로 계산함")
public record FeedMinigameRecordRequest(
        @Schema(description = "게임 완료 응답의 runId. 본인의 끝난 판이어야 함", example = "3f1c2a9e-6f4b-4f0e-9b7a-2d1c0e8a5b11")
        @NotBlank @Size(max = 36) String runId) { }
