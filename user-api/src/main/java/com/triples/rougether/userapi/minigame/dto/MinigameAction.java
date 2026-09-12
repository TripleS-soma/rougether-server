package com.triples.rougether.userapi.minigame.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record MinigameAction(@NotNull @Min(1) @Max(18000) Integer tick,
                             @NotNull MinigameDirection direction) {}
