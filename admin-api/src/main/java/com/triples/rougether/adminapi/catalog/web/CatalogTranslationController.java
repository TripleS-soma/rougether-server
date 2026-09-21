package com.triples.rougether.adminapi.catalog.web;

import com.triples.rougether.adminapi.catalog.service.CatalogTranslationService.Kind;
import com.triples.rougether.adminapi.catalog.service.CatalogTranslationService.Translation;
import com.triples.rougether.adminapi.catalog.service.CatalogTranslationService;
import com.triples.rougether.common.error.ErrorResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.NoSuchElementException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/catalog/{kind}/{id}/translations")
@RequiredArgsConstructor
public class CatalogTranslationController {
    private final CatalogTranslationService service;

    public record Request(@NotNull @Size(max = 1)
            Map<@Pattern(regexp = "en") String, @NotBlank @Size(max = 120) String> nameTranslations) {}

    @GetMapping
    public Translation get(@PathVariable Kind kind, @PathVariable Long id) { return service.get(kind, id); }

    // 기존 관리자 세션 인증과 CSRF 보호를 그대로 사용. {}로 번역을 제거하면 원문 폴백.
    @PutMapping
    public Translation update(@PathVariable Kind kind, @PathVariable Long id, @Valid @RequestBody Request request) {
        return service.update(kind, id, request.nameTranslations());
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ErrorResponse> notFound() {
        return ResponseEntity.status(404).body(ErrorResponse.of("CATALOG_TARGET_NOT_FOUND", "카탈로그 항목을 찾을 수 없습니다."));
    }
}
