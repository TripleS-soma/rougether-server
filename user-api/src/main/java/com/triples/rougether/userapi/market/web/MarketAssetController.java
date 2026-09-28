package com.triples.rougether.userapi.market.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.market.dto.MarketAssetIssueRequest;
import com.triples.rougether.userapi.market.dto.MarketAssetResponse;
import com.triples.rougether.userapi.market.service.MarketAssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 가구 거래소 종목 발행.
@Tag(name = "Market", description = "가구 거래소 API")
@RestController
@RequestMapping("/api/v1/market/assets")
public class MarketAssetController {

    private final MarketAssetService marketAssetService;

    public MarketAssetController(MarketAssetService marketAssetService) {
        this.marketAssetService = marketAssetService;
    }

    @Operation(summary = "AI 가구 발행",
            description = "사진으로 만든 내 AI 가구를 거래소에 에디션으로 발행합니다. 가구를 만든 회원이 인벤토리에 보유 중인 가구만, 가구당 한 번 발행할 수 있습니다. "
                    + "발행 수량(1~10)에는 내가 가진 1개가 포함되고, 나머지는 발행 재고(unissuedQuantity)가 되어 판매 주문으로 팔 수 있습니다. "
                    + "발행 즉시 거래가 열리며 수량은 바꿀 수 없습니다.")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public MarketAssetResponse issue(@CurrentUser AuthUser user,
                                     @Valid @RequestBody MarketAssetIssueRequest request) {
        return marketAssetService.issue(user.id(), request.userItemId(), request.totalSupply());
    }
}
