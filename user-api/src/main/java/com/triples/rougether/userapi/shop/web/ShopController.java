package com.triples.rougether.userapi.shop.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.shop.dto.ItemListResponse;
import com.triples.rougether.userapi.shop.dto.PurchaseResponse;
import com.triples.rougether.userapi.shop.service.ShopCommandService;
import com.triples.rougether.userapi.shop.service.ShopQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 상점 아이템 목록·구매. 인증된 사용자 기준(owned 판정), themeId 로 테마 필터.
@Tag(name = "Shop", description = "상점 관련 API")
@RestController
@RequestMapping("/api/v1/items")
public class ShopController {

    private final ShopQueryService shopQueryService;
    private final ShopCommandService shopCommandService;

    public ShopController(ShopQueryService shopQueryService, ShopCommandService shopCommandService) {
        this.shopQueryService = shopQueryService;
        this.shopCommandService = shopCommandService;
    }

    @Operation(summary = "상점 아이템 목록 조회",
            description = "활성 상점 아이템 목록을 반환합니다. 로그인한 회원의 보유 여부(owned)와 기본 배치 슬롯(defaultSlot)을 함께 내려줍니다.")
    @GetMapping
    public ItemListResponse getItems(@CurrentUser AuthUser user,
                                     @Parameter(description = "테마 ID (지정 시 해당 테마 아이템만 조회)")
                                     @RequestParam(required = false) Long themeId) {
        return shopQueryService.getItems(user.id(), themeId);
    }

    @Operation(summary = "아이템 구매",
            description = "다이아를 차감하고 아이템을 보유 목록에 지급합니다. 이미 보유한 아이템은 다시 구매할 수 없습니다.")
    @PostMapping("/{itemId}/purchase")
    public PurchaseResponse purchase(@CurrentUser AuthUser user,
                                     @Parameter(description = "구매할 아이템 ID") @PathVariable Long itemId) {
        return shopCommandService.purchase(user.id(), itemId);
    }
}
