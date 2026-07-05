package com.triples.rougether.userapi.gacha.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

// POST /api/v1/gacha/{id}/draw 요청. count = 1(단챠) 또는 10(10연).
public record GachaDrawRequest(
        @Schema(description = "뽑기 횟수. 1(단챠) 또는 10(10연)만 허용. 10연 비용은 단챠 비용(costAmount)의 5배",
                example = "1")
        @NotNull Integer count) {
}
