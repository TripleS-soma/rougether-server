package com.triples.rougether.userapi.routine.similarity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record SimilarItem(
        @Schema(description = "후보 종류. 허용값: ROUTINE(루틴), TODO(투두)", example = "ROUTINE")
        Kind kind,
        @Schema(description = "후보 id — kind에 따라 루틴 id 또는 투두 id", example = "12")
        Long id,
        @Schema(description = "후보 제목", example = "헬스")
        String title,
        @Schema(description = "유사도 점수(0~1). EXACT는 1.0, EMBEDDING은 코사인 유사도", example = "0.83")
        double score,
        @Schema(description = "판정 방식. 허용값: EXACT(정규화 제목 일치 — 공백·대소문자·전각 차이 무시), EMBEDDING(임베딩 코사인 유사도가 임계값 이상)", example = "EMBEDDING")
        MatchType matchType
) {

    public enum Kind { ROUTINE, TODO }

    public enum MatchType { EXACT, EMBEDDING }
}
