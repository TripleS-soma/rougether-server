package com.triples.rougether.adminapi.content.web;

import com.triples.rougether.adminapi.content.service.AdminContentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/content")
public class AdminContentController {

    private final AdminContentService contentService;

    public AdminContentController(AdminContentService contentService) {
        this.contentService = contentService;
    }

    @GetMapping
    public AdminContentCatalogResponse catalog() {
        return contentService.getCatalog();
    }

    @PostMapping("/themes")
    public AdminThemeResponse createTheme(@Valid @RequestBody AdminThemeRequest request) {
        return contentService.createTheme(request);
    }

    @PutMapping("/themes/{themeId}")
    public AdminThemeResponse updateTheme(
            @PathVariable Long themeId,
            @Valid @RequestBody AdminThemeRequest request) {
        return contentService.updateTheme(themeId, request);
    }

    @PostMapping("/items")
    public AdminItemResponse createItem(@Valid @RequestBody AdminItemRequest request) {
        return contentService.createItem(request);
    }

    @PutMapping("/items/{itemId}")
    public AdminItemResponse updateItem(
            @PathVariable Long itemId,
            @Valid @RequestBody AdminItemRequest request) {
        return contentService.updateItem(itemId, request);
    }

    @PostMapping("/characters")
    public AdminCharacterResponse createCharacter(@Valid @RequestBody AdminCharacterRequest request) {
        return contentService.createCharacter(request);
    }

    @PutMapping("/characters/{characterId}")
    public AdminCharacterResponse updateCharacter(
            @PathVariable Long characterId,
            @Valid @RequestBody AdminCharacterRequest request) {
        return contentService.updateCharacter(characterId, request);
    }
}
