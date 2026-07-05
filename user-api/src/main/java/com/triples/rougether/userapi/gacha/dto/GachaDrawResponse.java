package com.triples.rougether.userapi.gacha.dto;

import com.triples.rougether.domain.shared.CurrencyType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// POST /api/v1/gacha/{id}/draw 응답. 뽑은 결과 목록 + 차감/전환 후 재화별 지갑 잔액(코인·다이아 모두).
public record GachaDrawResponse(
        @Schema(description = "뽑기 결과 목록 (뽑은 순서대로, 10연이면 10개)")
        List<DrawResult> results,
        @Schema(description = "차감·전환 반영 후 재화별 지갑 잔액 (코인·다이아 모두 포함, 다이아 지갑 미발급이면 0)")
        List<WalletSummary> wallets) {

    // 한 번의 뽑기 결과. converted=true 면 중복 보유라 지급 대신 재화로 전환
    // (아이템 -> 다이아, 캐릭터 -> 코인. refundCurrencyType 이 전환된 재화).
    public record DrawResult(
            @Schema(description = "보상 종류 (ITEM=아이템 지급, CHARACTER=캐릭터 지급, CURRENCY=중복이라 재화 전환)",
                    example = "ITEM")
            String rewardType,
            @Schema(description = "아이템 ID (아이템 보상일 때. 아이템 중복으로 전환된 CURRENCY 결과에도 채워짐). "
                    + "GET /api/v1/items (상점 아이템 목록) 응답의 id 와 동일", example = "1")
            Long itemId,
            @Schema(description = "캐릭터 ID (캐릭터 보상일 때. 캐릭터 중복으로 전환된 CURRENCY 결과에도 채워짐). "
                    + "GET /api/v1/characters (캐릭터 마스터 목록) 응답의 id 와 동일", example = "2")
            Long characterId,
            @Schema(description = "보상 이름", example = "Bakery Morning Set - Breakfast Table")
            String name,
            @Schema(description = "보상 이미지 asset key (CDN base URL 과 조합해 사용)",
                    example = "items/bakery-morning/furniture/bakery-morning-breakfast-table.png")
            String assetKey,
            @Schema(description = "등급. 허용값: 일반(70%), 희귀(25%), 전설(5%). 등급 미부여 풀(캐릭터 뽑기 등)이면 null",
                    example = "일반")
            String rarity,
            @Schema(description = "중복 보유로 재화 전환됐는지", example = "false")
            boolean converted,
            @Schema(description = "전환된 재화 종류 (converted=true 일 때, 아이템은 DIAMOND·캐릭터는 COIN)",
                    example = "DIAMOND")
            CurrencyType refundCurrencyType,
            @Schema(description = "전환 금액 (converted=true 일 때, 아이템 30·캐릭터 200)", example = "30")
            Integer refundAmount) {
    }

    public record WalletSummary(
            @Schema(description = "재화 종류 (COIN=루틴 보상·뽑기, DIAMOND=상점 구매·중복 전환)", example = "COIN")
            CurrencyType currencyType,
            @Schema(description = "차감·전환 반영 후 잔액", example = "750")
            int balance) {
    }
}
