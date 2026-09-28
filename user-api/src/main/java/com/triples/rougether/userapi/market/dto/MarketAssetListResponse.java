package com.triples.rougether.userapi.market.dto;

import com.triples.rougether.domain.market.entity.MarketAssetStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// GET /api/v1/market/assets 응답. 페이지네이션 규약({items, page, size, totalElements}) 적용.
public record MarketAssetListResponse(
        List<AssetCard> items,
        @Schema(description = "페이지 번호 (0부터)", example = "0")
        int page,
        @Schema(description = "페이지 크기", example = "20")
        int size,
        @Schema(description = "거래 중인 전체 종목 수", example = "42")
        long totalElements) {

    public record AssetCard(
            @Schema(description = "종목 ID. 종목 상세(GET /api/v1/market/assets/{assetId})와 주문 접수의 assetId 로 사용", example = "1")
            Long assetId,
            @Schema(description = "가구 아이템 ID", example = "320")
            Long itemId,
            @Schema(description = "가구 이름", example = "고양이 소파")
            String name,
            @Schema(description = "가구 이미지 asset key. CDN base URL과 조합해 이미지 URL로 사용", example = "furniture/photo/abc.png")
            String assetKey,
            @Schema(description = "제작자 닉네임. 제작자가 탈퇴했으면 null", example = "영희", nullable = true)
            String creatorNickname,
            @Schema(description = "총 발행 수량", example = "5")
            int totalSupply,
            @Schema(description = "가장 싼 판매 가격(코인). 판매 대기 주문이 없으면 null", example = "28", nullable = true)
            Integer bestAskPrice,
            @Schema(description = "판매 대기 중인 남은 수량 합계", example = "3")
            int askQuantity,
            @Schema(description = "최근 체결가(코인). 체결 이력이 없으면 null", example = "30", nullable = true)
            Integer lastTradePrice,
            @Schema(description = "종목 상태. 목록에는 ACTIVE(거래 중)만 나옴", example = "ACTIVE")
            MarketAssetStatus status) {
    }
}
