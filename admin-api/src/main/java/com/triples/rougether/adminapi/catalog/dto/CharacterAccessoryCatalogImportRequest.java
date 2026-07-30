package com.triples.rougether.adminapi.catalog.dto;

import com.triples.rougether.adminapi.accessoryrender.dto.AccessoryRenderProfileImportRequest;
import java.util.List;

public record CharacterAccessoryCatalogImportRequest(
        CatalogImportRequest catalog,
        List<AccessoryRenderProfileImportRequest> renderProfiles) {
}
