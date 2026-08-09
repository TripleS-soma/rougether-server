package com.triples.rougether.adminapi.catalog.dto;

import jakarta.validation.constraints.NotNull;

public record CatalogActiveUpdateRequest(@NotNull Boolean active) {
}
