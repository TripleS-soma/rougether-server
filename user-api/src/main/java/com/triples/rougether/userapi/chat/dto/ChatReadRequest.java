package com.triples.rougether.userapi.chat.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ChatReadRequest(@NotNull @PositiveOrZero Long lastReadSequence) {}
