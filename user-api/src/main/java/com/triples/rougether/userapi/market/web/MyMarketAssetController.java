package com.triples.rougether.userapi.market.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.market.dto.MarketAssetListResponse;
import com.triples.rougether.userapi.market.service.MarketQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Market", description = "가구 거래소 API")
@RestController
@RequestMapping("/api/v1/me/market/assets")
public class MyMarketAssetController {

    private final MarketQueryService query;

    public MyMarketAssetController(MarketQueryService query) {
        this.query = query;
    }

    @Operation(summary = "내 판매 관리 가구", description = "제작하거나 보유하거나 판매 중인 종목을 조회합니다. 판매 주문 없이 비노출 중인 가구도 포함합니다.")
    @GetMapping
    public MarketAssetListResponse list(@CurrentUser AuthUser user,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return query.myAssets(user.id(), page, size);
    }
}
