package com.triples.rougether.userapi.gacha.dto;

import jakarta.validation.constraints.NotNull;

// POST /api/v1/gacha/{id}/draw 요청. count = 1(단챠) 또는 10(10연).
public record GachaDrawRequest(@NotNull Integer count) {
}
