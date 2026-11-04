package com.triples.rougether.userapi.market.web;

import com.triples.rougether.userapi.global.security.*;
import com.triples.rougether.userapi.market.service.MarketShopService;
import com.triples.rougether.userapi.market.dto.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/market/shop")
@RequiredArgsConstructor
@Tag(name = "Market Shop", description = "제작자가 가격을 정하고 원본을 보관하는 가구 판매")
public class MarketShopController {
    private final MarketShopService shop;
    public record SellRequest(@NotNull Long userItemId, @Min(1) @Max(1000) int price) { }
    public record UpdateRequest(@Min(1) @Max(1000) int price, @NotNull Boolean selling) { }
    public record BuyRequest(@NotNull UUID requestId, @Min(1) @Max(1000) int expectedPrice) { }

    @GetMapping
    public MarketListingResponse.Listings list(@CurrentUser AuthUser user,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return shop.list(user.id(), mine, page, size);
    }

    @GetMapping("/{id}")
    public MarketListingResponse get(@CurrentUser AuthUser user, @PathVariable Long id) { return shop.get(user.id(), id); }

    @PostMapping
    @Operation(summary = "내가 만든 가구 판매 등록", description = "가격은 1~1000코인. 원본을 보관하며 수량 제한 없이 판매합니다.")
    public MarketListingResponse sell(@CurrentUser AuthUser user, @Valid @RequestBody SellRequest request) {
        return shop.sell(user.id(), request.userItemId(), request.price());
    }

    @PutMapping("/{id}")
    public MarketListingResponse update(@CurrentUser AuthUser user, @PathVariable Long id, @Valid @RequestBody UpdateRequest request) {
        return shop.update(user.id(), id, request.price(), request.selling());
    }

    @PostMapping("/{id}/purchase")
    @Operation(summary = "표시 가격으로 즉시 구매", description = "한 번만 구매할 수 있으며 같은 requestId 재요청은 같은 영수증을 반환합니다. 가격 변경 시 결제하지 않습니다.")
    public MarketPurchaseResponse buy(@CurrentUser AuthUser user, @PathVariable Long id, @Valid @RequestBody BuyRequest request) {
        return shop.buy(user.id(), id, request.requestId().toString(), request.expectedPrice());
    }
}
