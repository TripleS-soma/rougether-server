package com.triples.rougether.adminapi.catalog.web;

import com.triples.rougether.adminapi.accessoryrender.error.AccessoryRenderProfileInvalidException;
import com.triples.rougether.adminapi.catalog.dto.CharacterAccessoryCatalogImportRequest;
import com.triples.rougether.adminapi.catalog.dto.CharacterAccessoryCatalogImportResult;
import com.triples.rougether.adminapi.catalog.error.CatalogImportInvalidException;
import com.triples.rougether.adminapi.catalog.service.CharacterAccessoryCatalogImportService;
import com.triples.rougether.common.error.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/catalog/character-accessories")
public class CharacterAccessoryCatalogImportController {

    private final CharacterAccessoryCatalogImportService importService;

    public CharacterAccessoryCatalogImportController(
            CharacterAccessoryCatalogImportService importService) {
        this.importService = importService;
    }

    @PostMapping("/import")
    public CharacterAccessoryCatalogImportResult importAll(
            @RequestBody CharacterAccessoryCatalogImportRequest request) {
        return importService.importAll(request);
    }

    @ExceptionHandler({
            AccessoryRenderProfileInvalidException.class,
            CatalogImportInvalidException.class
    })
    public ResponseEntity<ErrorResponse> handleInvalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "CHARACTER_ACCESSORY_CATALOG_IMPORT_INVALID",
                exception.getMessage()));
    }
}
