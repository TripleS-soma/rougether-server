package com.triples.rougether.adminapi.characterpose.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CharacterPoseAdminRequest(
        @NotBlank
        @Size(max = 50)
        String characterCode,
        @NotBlank
        @Pattern(regexp = "[a-z0-9][a-z0-9_-]{0,39}")
        String code,
        @NotBlank
        @Size(max = 255)
        String assetKey,
        @NotNull
        @Min(0)
        Integer sortOrder,
        boolean active) {
}
