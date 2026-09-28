package com.triples.rougether.userapi.market.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.market.dto.MyMarketOrderListResponse;
import com.triples.rougether.userapi.market.dto.MyMarketOrderStatus;
import com.triples.rougether.userapi.market.service.MarketQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 내 거래소 주문 목록.
@Tag(name = "Market", description = "가구 거래소 API")
@RestController
@RequestMapping("/api/v1/me/market/orders")
public class MyMarketOrderController {

    private final MarketQueryService marketQueryService;

    public MyMarketOrderController(MarketQueryService marketQueryService) {
        this.marketQueryService = marketQueryService;
    }

    @Operation(summary = "내 거래소 주문 목록 조회",
            description = "내 주문을 최신순으로 한 페이지씩 조회합니다. status=OPEN 이면 대기 중인 주문을, CLOSED 면 체결 완료·취소·만료된 "
                    + "주문을 내려줍니다. 미지정 시 status=OPEN, page=0, size=20 으로 조회합니다. "
                    + "대기 중인 주문은 orderId 로 주문 취소(POST /api/v1/market/orders/{orderId}/cancel)를 할 수 있습니다.")
    @GetMapping
    public MyMarketOrderListResponse list(
            @CurrentUser AuthUser user,
            @Parameter(description = "주문 상태 묶음. 허용값: OPEN(대기 중), CLOSED(체결 완료·취소·만료)")
            @RequestParam(defaultValue = "OPEN") MyMarketOrderStatus status,
            @Parameter(description = "페이지 번호 (0부터)") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기 (1~100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return marketQueryService.myOrders(user.id(), status, page, size);
    }
}
