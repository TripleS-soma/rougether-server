package com.triples.rougether.userapi.routine.similarity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

// 캘린더 임포트 미리보기: 날짜·제목 목록을 받아 각 항목마다 그 날짜의 내 루틴·투두 중 유사한 것을 찾는다
public record SimilarityRequest(
        @Schema(description = "비교할 항목 목록(1~200개). 요청 순서대로 응답한다")
        @NotNull @Size(min = 1, max = 200) @Valid List<@NotNull Item> items
) {

    public record Item(
            @Schema(description = "비교 기준 날짜(YYYY-MM-DD). 그 날짜의 반복 대상 루틴과 마감 투두가 후보가 된다", example = "2026-08-20")
            @NotNull LocalDate date,
            @Schema(description = "비교할 제목(앞뒤 공백 제외 1~160자). 캘린더 이벤트 제목 등", example = "PT")
            @NotBlank @Size(max = 160) String title
    ) {
        // 길이 검증·비교 모두 trim 기준 — 역직렬화 직후 잘라 두면 @Size 가 trim 된 길이를 본다
        public Item {
            title = title == null ? null : title.trim();
        }
    }
}
