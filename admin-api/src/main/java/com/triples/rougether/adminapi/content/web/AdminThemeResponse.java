package com.triples.rougether.adminapi.content.web;

import com.triples.rougether.domain.shop.entity.Theme;

public record AdminThemeResponse(
        Long id,
        String code,
        String name,
        String coverImageKey,
        boolean active) {

    public static AdminThemeResponse from(Theme theme) {
        return new AdminThemeResponse(
                theme.getId(),
                theme.getCode(),
                theme.getName(),
                theme.getCoverImageKey(),
                theme.isActive());
    }
}
