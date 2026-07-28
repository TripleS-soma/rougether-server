package com.triples.rougether.userapi.gacha.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

// POST /api/v1/gacha/{id}/draw 요청. count = 1(단챠) 또는 6(5+1회).
public record GachaDrawRequest(
        @Schema(description = "뽑기 횟수. 신규 요청은 1(단챠) 또는 6(5+1회). "
                + "구버전 호환용 count=10도 5+1회와 동일하게 결과 6개·비용 5배로 처리. "
                + "5+1회 비용은 단챠 비용(costAmount)의 5배",
                example = "1")
        @NotNull Integer count) {
}
