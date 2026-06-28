package com.triples.rougether.adminapi.content.web;

import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.PlacementType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AdminItemRequest(
        @NotNull Long themeId,
        @NotBlank @Size(max = 50) String categoryCode,
        @NotNull PlacementType placementType,
        @Size(max = 40) String surfaceSlotType,
        @Size(max = 40) String characterSlotType,
        @NotBlank @Size(max = 120) String name,
        CurrencyType purchaseCurrencyType,
        @PositiveOrZero Integer priceAmount,
        @NotBlank @Size(max = 255) String assetKey,
        Boolean limited,
        Boolean active) {
}
