package com.triples.rougether.userapi.gacha.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// GET /api/v1/gacha/{id}/rewards 응답. 확률·weight 없이 현재 활성 보상만 노출함.
public record GachaRewardListResponse(
        @Schema(description = "현재 활성 풀에 등록된 보상 목록 (풀 엔트리 ID 오름차순)")
        List<GachaRewardResponse> items) {

    public record GachaRewardResponse(
            @Schema(description = "보상 종류 (ITEM=아이템, CHARACTER=캐릭터)", example = "ITEM")
            String rewardType,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "아이템 ID (ITEM 보상일 때)", example = "1")
            Long itemId,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "캐릭터 ID (CHARACTER 보상일 때)", example = "2")
            Long characterId,
            @Schema(description = "보상 이름", example = "한옥 좌식상")
            String name,
            @Schema(description = "보상 이미지 asset key (CDN base URL 과 조합해 사용)",
                    example = "items/calm-hanok/furniture/low-table.png")
            String assetKey,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "등급. 등급 미부여 풀은 null", example = "희귀")
            String rarity,
            @Schema(description = "인증 사용자가 해당 보상을 현재 보유 중인지", example = "true")
            boolean owned,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "아이템 카테고리 (ITEM 보상일 때)", example = "character_accessory")
            String categoryCode,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "아이템 배치 종류 (ITEM 보상일 때)", example = "character")
            String placementType,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "방 표면 슬롯 종류 (해당 ITEM 보상일 때)", example = "wallpaper")
            String surfaceSlotType,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "캐릭터 착용 슬롯 종류 (해당 ITEM 보상일 때)", example = "eyewear")
            String characterSlotType) {
    }
}
