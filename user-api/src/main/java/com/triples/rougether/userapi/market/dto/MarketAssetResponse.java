package com.triples.rougether.userapi.market.dto;

import com.triples.rougether.domain.market.entity.MarketAssetStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record MarketAssetResponse(
        @Schema(description = "종목 ID", example = "1")
        Long assetId,
        @Schema(description = "가구 아이템 ID", example = "320")
        Long itemId,
        @Schema(description = "가구 이름", example = "고양이 소파")
        String name,
        @Schema(description = "가구 이미지 asset key. CDN base URL과 조합해 사용", example = "furniture/photo/abc.png")
        String assetKey,
        @Schema(description = "제작자 닉네임. 제작자가 탈퇴했으면 null", example = "영희", nullable = true)
        String creatorNickname,
        @Schema(description = "요청자가 제작자인지 여부", example = "true")
        boolean isCreator,
        @Schema(description = "총 발행 수량", example = "5")
        int totalSupply,
        @Schema(description = "제작자가 아직 팔지 않은 발행 재고", example = "4")
        int unissuedQuantity,
        @Schema(description = "종목 상태. 허용값: ACTIVE(거래 중), SUSPENDED(거래 정지)", example = "ACTIVE")
        MarketAssetStatus status,
        @Schema(description = "최근 체결가. 체결 이력이 없으면 null", example = "28", nullable = true)
        Integer lastTradePrice,
        @Schema(description = "요청자가 이 가구를 인벤토리에 보유 중인지 여부(판매 등록으로 맡긴 것은 제외)", example = "true")
        boolean owned,
        @Schema(description = "판매 호가. 가격대별 합산, 가격 오름차순, 최대 10단계")
        List<PriceLevel> asks,
        @Schema(description = "구매 호가. 가격대별 합산, 가격 내림차순, 최대 10단계")
        List<PriceLevel> bids
) {

    public record PriceLevel(
            @Schema(description = "가격(코인)", example = "30")
            int price,
            @Schema(description = "해당 가격의 남은 수량 합계", example = "2")
            int quantity
    ) {
    }
}
