package com.triples.rougether.userapi.market.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.triples.rougether.domain.market.entity.OrderSide;
import com.triples.rougether.domain.market.entity.OrderSource;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record PlaceOrderRequest(
        @Schema(description = "요청 ID(UUID). 버튼을 누를 때마다 새로 만들고, 네트워크 재시도에는 같은 값을 다시 보냄. "
                + "같은 값으로 다시 요청하면 새로 접수하지 않고 기존 접수를 돌려줌", example = "3f2b8c1e-8d4a-4f1b-9c2e-7a6d5e4f3b21")
        @NotNull @Pattern(regexp = UUID_PATTERN) String requestId,
        @Schema(description = "종목 ID. 거래소 종목 조회 응답의 assetId 값", example = "1")
        @NotNull Long assetId,
        @Schema(description = "주문 방향. 허용값: BUY(구매), SELL(판매)", example = "BUY")
        @NotNull OrderSide side,
        @Schema(description = "개당 가격(코인, 1~1,000 정수). 구매는 이 가격 이하 매물과, 판매는 이 가격 이상 구매 주문과 체결됨", example = "30")
        @NotNull @Min(1) @Max(1000) Integer price,
        @Schema(description = "수량. 구매와 내 보유분 판매는 1, 제작자의 발행 재고 판매만 1~남은 재고(최대 10)", example = "1")
        @NotNull @Min(1) @Max(10) Integer quantity,
        @Schema(description = "판매 물량 출처. side=SELL일 때만 필수. 허용값: INVENTORY(내가 가진 가구), ISSUANCE(제작자의 발행 재고). "
                + "구매(BUY)에서는 비워 둠", example = "INVENTORY", nullable = true)
        OrderSource source
) {

    static final String UUID_PATTERN = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    // 방향·출처·수량 조합 검증. 위반 시 VALIDATION_FAILED(400).
    @JsonIgnore
    @AssertTrue
    public boolean isShapeValid() {
        if (side == null || quantity == null) {
            return true; // 개별 @NotNull 이 보고함
        }
        return switch (side) {
            case BUY -> source == null && quantity == 1;
            case SELL -> source == OrderSource.ISSUANCE || (source == OrderSource.INVENTORY && quantity == 1);
        };
    }
}
