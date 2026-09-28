package com.triples.rougether.userapi.market.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.market.dto.CancelOrderRequest;
import com.triples.rougether.userapi.market.dto.MarketCommandAcceptedResponse;
import com.triples.rougether.userapi.market.dto.MarketCommandResponse;
import com.triples.rougether.userapi.market.dto.PlaceOrderRequest;
import com.triples.rougether.userapi.market.service.MarketCommandQueryService;
import com.triples.rougether.userapi.market.service.MarketOrderCommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 가구 거래소 주문 접수·취소·접수 결과 조회.
@Tag(name = "Market", description = "가구 거래소 API")
@RestController
@RequestMapping("/api/v1/market")
public class MarketOrderController {

    private final MarketOrderCommandService marketOrderCommandService;
    private final MarketCommandQueryService marketCommandQueryService;

    public MarketOrderController(MarketOrderCommandService marketOrderCommandService,
                                 MarketCommandQueryService marketCommandQueryService) {
        this.marketOrderCommandService = marketOrderCommandService;
        this.marketCommandQueryService = marketCommandQueryService;
    }

    @Operation(summary = "주문 접수",
            description = "지정가로 구매·판매 주문을 접수합니다. 접수할 때 대가를 먼저 맡깁니다: 구매는 가격×수량만큼 코인을 차감하고, "
                    + "내 가구 판매는 가구를 인벤토리와 방에서 빼며, 발행 재고 판매는 남은 재고에서 뺍니다. "
                    + "체결은 비동기로 처리되므로 응답의 commandId로 접수 결과 조회를 호출해 결과를 확인합니다. "
                    + "한 사람은 같은 가구를 1개만 가질 수 있어, 보유 중이거나 구매를 기다리는 가구는 구매할 수 없습니다. "
                    + "발행 재고 판매는 제작자만 할 수 있습니다. 같은 requestId로 다시 요청하면 새로 접수하지 않고 기존 접수를 돌려줍니다.")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PostMapping("/orders")
    public MarketCommandAcceptedResponse place(@CurrentUser AuthUser user,
                                               @Valid @RequestBody PlaceOrderRequest request) {
        return marketOrderCommandService.place(user.id(), request);
    }

    @Operation(summary = "주문 취소 접수",
            description = "대기 중(OPEN)인 내 주문의 취소를 접수합니다. 맡긴 코인이나 가구는 취소가 처리될 때 돌려받으며, "
                    + "돌려받은 가구는 인벤토리로 돌아오고 방 배치는 복구되지 않습니다. "
                    + "처리 결과는 응답의 commandId로 접수 결과 조회를 호출해 확인합니다.")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PostMapping("/orders/{orderId}/cancel")
    public MarketCommandAcceptedResponse cancel(@CurrentUser AuthUser user,
                                                @Parameter(description = "취소할 주문 ID. 접수 결과 조회 응답의 order.orderId 값")
                                                @PathVariable Long orderId,
                                                @Valid @RequestBody CancelOrderRequest request) {
        return marketOrderCommandService.cancel(user.id(), orderId, request.requestId());
    }

    @Operation(summary = "주문 접수 결과 조회",
            description = "내가 낸 주문·취소 접수의 처리 결과를 조회합니다. PENDING이면 아직 처리 전이므로 잠시 뒤 다시 조회합니다. "
                    + "APPLIED인 주문 접수는 만들어진 주문(order)과 체결 수량을 함께 내려줍니다.")
    @GetMapping("/commands/{commandId}")
    public MarketCommandResponse getCommand(@CurrentUser AuthUser user,
                                            @Parameter(description = "접수 ID. 주문 접수·취소 응답의 commandId 값")
                                            @PathVariable Long commandId) {
        return marketCommandQueryService.get(user.id(), commandId);
    }
}
