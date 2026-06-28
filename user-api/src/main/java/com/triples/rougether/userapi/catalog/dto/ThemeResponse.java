package com.triples.rougether.userapi.catalog.dto;

import com.triples.rougether.domain.shop.entity.Theme;

public record ThemeResponse(
        Long id,
        String code,
        String name,
        String coverImageKey) {

    public static ThemeResponse from(Theme theme) {
        return new ThemeResponse(
                theme.getId(),
                theme.getCode(),
                theme.getName(),
                theme.getCoverImageKey());
    }
}
