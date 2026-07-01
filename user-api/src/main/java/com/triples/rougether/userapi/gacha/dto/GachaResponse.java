package com.triples.rougether.userapi.gacha.dto;

import com.triples.rougether.domain.gacha.entity.Gacha;
import com.triples.rougether.domain.shared.CurrencyType;

// 뽑기 머신 정보. GET /api/v1/gacha, GET /api/v1/gacha/{id}.
public record GachaResponse(
        Long gachaId,
        String code,
        String name,
        Long themeId,
        CurrencyType costCurrencyType,
        int costAmount,
        int drawCount,
        boolean active) {

    public static GachaResponse of(Gacha gacha) {
        return new GachaResponse(
                gacha.getId(),
                gacha.getCode(),
                gacha.getName(),
                gacha.getTheme().getId(),
                gacha.getCostCurrencyType(),
                gacha.getCostAmount(),
                gacha.getDrawCount(),
                gacha.isActive());
    }
}
