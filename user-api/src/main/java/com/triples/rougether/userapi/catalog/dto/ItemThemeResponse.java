package com.triples.rougether.userapi.catalog.dto;

import com.triples.rougether.domain.shop.entity.Theme;

public record ItemThemeResponse(
        Long id,
        String code,
        String name,
        String coverImageKey) {

    public static ItemThemeResponse from(Theme theme) {
        return new ItemThemeResponse(
                theme.getId(),
                theme.getCode(),
                theme.getName(),
                theme.getCoverImageKey());
    }
}
