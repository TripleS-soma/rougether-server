package com.triples.rougether.userapi.house.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record HouseOrderUpdateRequest(
        @Schema(description = "내 활성 집 ID 전체 목록. 배열 순서대로 집 탭에 노출", example = "[12, 5, 31]")
        @NotEmpty List<@NotNull Long> houseIds) {
}
