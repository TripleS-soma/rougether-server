package com.triples.rougether.userapi.gacha.dto;

import com.triples.rougether.domain.gacha.entity.Gacha;
import com.triples.rougether.domain.shared.CurrencyType;
import io.swagger.v3.oas.annotations.media.Schema;

// 뽑기 머신 정보. GET /api/v1/gacha, GET /api/v1/gacha/{id}.
public record GachaResponse(
        @Schema(description = "뽑기 머신 ID. 상세 조회·뽑기 실행의 경로 {id} 에 사용", example = "1")
        Long gachaId,
        @Schema(description = "뽑기 머신 코드", example = "bakery_morning")
        String code,
        @Schema(description = "뽑기 머신 이름", example = "베이커리 모닝 뽑기")
        String name,
        @Schema(description = "가구 뽑기의 대상 테마 ID (캐릭터 뽑기는 null). "
                + "GET /api/v1/items 응답의 theme.id 와 동일하며 themeId 필터에 사용 가능", example = "3")
        Long themeId,
        @Schema(description = "뽑기 비용 재화. 허용값: COIN(코인 — 루틴 보상·뽑기), DIAMOND(다이아 — 상점 구매). "
                + "표시용이며 실제 뽑기 차감은 항상 COIN", example = "COIN")
        CurrencyType costCurrencyType,
        @Schema(description = "단챠 1회 비용 (count=10 요청 시 이 값의 5배 차감)", example = "250")
        int costAmount,
        @Schema(description = "1회 실행당 뽑는 개수", example = "1")
        int drawCount,
        @Schema(description = "운영 중 여부 (목록 조회에는 true 인 머신만 내려감. 뽑기 실행도 운영 중인 머신만 가능)",
                example = "true")
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
