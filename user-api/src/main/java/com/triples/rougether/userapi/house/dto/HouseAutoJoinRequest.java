package com.triples.rougether.userapi.house.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record HouseAutoJoinRequest(
        @Schema(description = "온보딩 자동 입주 허용. 공개 집에만 실제 적용되며 기본값은 false")
        @NotNull Boolean enabled) {
}
