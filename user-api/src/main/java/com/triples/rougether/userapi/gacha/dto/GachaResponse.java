package com.triples.rougether.userapi.gacha.dto;

import com.triples.rougether.domain.gacha.entity.Gacha;
import com.triples.rougether.domain.gacha.entity.GachaCategory;
import com.triples.rougether.domain.shared.CurrencyType;
import io.swagger.v3.oas.annotations.media.Schema;

// 뽑기 머신 정보. GET /api/v1/gacha, GET /api/v1/gacha/{id}.
public record GachaResponse(
        @Schema(description = "뽑기 머신 ID. 상세 조회·뽑기 실행의 경로 {id} 에 사용", example = "1")
        Long gachaId,
        @Schema(description = "뽑기 머신 코드", example = "wallpaper_gacha")
        String code,
        @Schema(description = "뽑기 머신 이름", example = "벽지 뽑기")
        String name,
        @Schema(description = "공개 장식 뽑기 카테고리. WALLPAPER, FLOOR, FURNITURE. "
                + "이전 머신 직접 상세 조회에서는 null일 수 있음", example = "WALLPAPER")
        GachaCategory category,
        @Schema(description = "이전 테마 머신 호환 필드. 카테고리 뽑기는 모든 테마를 포함하며 null")
        Long themeId,
        @Schema(description = "뽑기 머신에 표시할 투명 선물상자 이미지 asset key. CDN base URL과 조합해 사용",
                example = "items/643162b1-276e-4c93-98cb-9a5c706f677f.png")
        String giftBoxAssetKey,
        @Schema(description = "뽑기 비용 재화. 허용값: COIN(코인 — 루틴 보상·뽑기), DIAMOND(다이아 — 상점 구매). "
                + "표시용이며 실제 뽑기 차감은 항상 COIN", example = "COIN")
        CurrencyType costCurrencyType,
        @Schema(description = "단챠 1회 비용 (count=6 요청 시 이 값의 5배 차감)", example = "25")
        int costAmount,
        @Schema(description = "1회 실행당 뽑는 개수", example = "1")
        int drawCount,
        @Schema(description = "운영 중 여부 (목록 조회에는 true 인 머신만 내려감. 뽑기 실행도 운영 중인 머신만 가능)",
                example = "true")
        boolean active) {

    public static GachaResponse of(Gacha gacha, String giftBoxAssetKey) {
        return new GachaResponse(
                gacha.getId(),
                gacha.getCode(),
                gacha.getName(),
                gacha.getCategory(),
                gacha.getTheme() != null ? gacha.getTheme().getId() : null,
                giftBoxAssetKey,
                gacha.getCostCurrencyType(),
                gacha.getCostAmount(),
                gacha.getDrawCount(),
                gacha.isActive());
    }
}
