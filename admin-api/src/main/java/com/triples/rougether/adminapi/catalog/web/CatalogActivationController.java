package com.triples.rougether.adminapi.catalog.web;

import com.triples.rougether.adminapi.catalog.dto.CatalogActiveUpdateRequest;
import com.triples.rougether.adminapi.catalog.dto.CatalogCharacterListResponse;
import com.triples.rougether.adminapi.catalog.dto.CatalogCharacterRow;
import com.triples.rougether.adminapi.catalog.dto.CatalogItemListResponse;
import com.triples.rougether.adminapi.catalog.dto.CatalogItemRow;
import com.triples.rougether.adminapi.catalog.error.CatalogActivationInvalidException;
import com.triples.rougether.adminapi.catalog.service.CatalogActivationService;
import com.triples.rougether.common.error.ErrorResponse;
import jakarta.validation.Valid;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 카탈로그 화면(/catalog)의 목록 조회 + 사용/미사용 토글.
// import(적재)와 달리 화면 JS 가 CSRF 헤더를 붙여 호출한다.
@RestController
@RequestMapping("/admin/catalog")
public class CatalogActivationController {

    private final CatalogActivationService service;

    public CatalogActivationController(CatalogActivationService service) {
        this.service = service;
    }

    @GetMapping("/items")
    public CatalogItemListResponse getItems() {
        return service.getItems();
    }

    @GetMapping("/characters")
    public CatalogCharacterListResponse getCharacters() {
        return service.getCharacters();
    }

    @PutMapping("/items/{itemId}/active")
    public CatalogItemRow updateItemActive(@PathVariable Long itemId,
                                           @Valid @RequestBody CatalogActiveUpdateRequest request) {
        return service.updateItemActive(itemId, request.active());
    }

    @PutMapping("/characters/{characterId}/active")
    public CatalogCharacterRow updateCharacterActive(
            @PathVariable Long characterId,
            @Valid @RequestBody CatalogActiveUpdateRequest request) {
        return service.updateCharacterActive(characterId, request.active());
    }

    @ExceptionHandler(CatalogActivationInvalidException.class)
    public ResponseEntity<ErrorResponse> handleInvalid(CatalogActivationInvalidException exception) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("CATALOG_ACTIVATION_INVALID", exception.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("CATALOG_TARGET_NOT_FOUND", exception.getMessage()));
    }
}
