package com.triples.rougether.userapi.gacha.dto;

import com.triples.rougether.domain.gacha.entity.Gacha;
import com.triples.rougether.domain.shared.CurrencyType;
import io.swagger.v3.oas.annotations.media.Schema;

// 뽑기 머신 정보. GET /api/v1/gacha, GET /api/v1/gacha/{id}.
public record GachaResponse(
        @Schema(description = "뽑기 머신 ID", example = "1")
        Long gachaId,
        @Schema(description = "뽑기 머신 코드", example = "bakery_morning")
        String code,
        @Schema(description = "뽑기 머신 이름", example = "베이커리 모닝 뽑기")
        String name,
        @Schema(description = "가구 뽑기의 대상 테마 ID (캐릭터 뽑기는 null)", example = "3")
        Long themeId,
        @Schema(description = "뽑기 비용 재화", example = "COIN")
        CurrencyType costCurrencyType,
        @Schema(description = "단챠 1회 비용 (10연은 5배)", example = "250")
        int costAmount,
        @Schema(description = "1회 실행당 뽑는 개수", example = "1")
        int drawCount,
        @Schema(description = "운영 중 여부", example = "true")
        boolean active) {

    public static GachaResponse of(Gacha gacha) {
        return new GachaResponse(
                gacha.getId(),
                gacha.getCode(),
                gacha.getName(),
                gacha.getTheme() != null ? gacha.getTheme().getId() : null,
                gacha.getCostCurrencyType(),
                gacha.getCostAmount(),
                gacha.getDrawCount(),
                gacha.isActive());
    }
}
