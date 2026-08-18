package com.triples.rougether.userapi.routine.similarity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

public record SimilarityResponse(
        @Schema(description = "임베딩 비교가 실제로 적용됐는지. false면 임베딩을 쓸 수 없어(키 미설정·외부 API 장애) 정규화 일치(EXACT)만으로 판정한 결과 — 힌트가 덜 잡힐 수 있으니 참고용", example = "true")
        boolean embeddingApplied,
        @Schema(description = "요청 items와 같은 순서의 결과")
        List<Item> items
) {

    public record Item(
            @Schema(description = "요청한 날짜", example = "2026-08-20")
            LocalDate date,
            @Schema(description = "요청한 제목(trim 적용)", example = "PT")
            String title,
            @Schema(description = "유사한 루틴·투두가 하나라도 있는지", example = "true")
            boolean hasSimilar,
            @Schema(description = "유사한 후보. score 내림차순 최대 3개, 없으면 빈 배열")
            List<SimilarItem> similar
    ) {
    }
}
