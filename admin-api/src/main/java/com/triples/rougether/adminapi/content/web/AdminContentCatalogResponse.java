package com.triples.rougether.adminapi.content.web;

import java.util.List;

public record AdminContentCatalogResponse(
        List<AdminThemeResponse> themes,
        List<AdminItemResponse> items,
        List<AdminCharacterResponse> characters) {
}
