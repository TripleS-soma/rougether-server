package com.triples.rougether.adminapi.content.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AdminCharacterRequest(
        @NotBlank @Size(max = 50) String code,
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 255) String baseAssetKey,
        @NotNull @PositiveOrZero Integer sortOrder,
        Boolean active) {
}
