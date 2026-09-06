package com.triples.rougether.userapi.furniture.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record FurnitureFeedbackRequest(@NotNull UUID requestId, @NotBlank @Size(max = 500) String feedback) { }
