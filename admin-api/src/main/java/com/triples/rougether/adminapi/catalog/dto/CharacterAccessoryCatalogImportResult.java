package com.triples.rougether.adminapi.catalog.dto;

import com.triples.rougether.adminapi.accessoryrender.dto.AccessoryRenderProfileImportResult;

public record CharacterAccessoryCatalogImportResult(
        CatalogImportResult catalog,
        AccessoryRenderProfileImportResult renderProfiles) {
}
