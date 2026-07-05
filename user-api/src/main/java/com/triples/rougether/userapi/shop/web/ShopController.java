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
            description = "활성(active) 상점 아이템 목록을 반환합니다. 로그인한 회원의 보유 여부(owned)와 기본 배치 슬롯(defaultSlot)을 함께 내려줍니다. "
                    + "themeId 를 지정하면 해당 테마의 아이템만, 미지정 시 전체 활성 아이템을 반환합니다. "
                    + "purchaseCurrencyType·priceAmount 가 null 인 아이템은 뽑기 전용이라 구매 대상이 아닙니다. "
                    + "별도 정렬 순서는 보장하지 않으므로 화면 정렬은 클라이언트에서 처리합니다.")
    @GetMapping
    public ItemListResponse getItems(@CurrentUser AuthUser user,
                                     @Parameter(description = "테마 ID (지정 시 해당 테마 아이템만 조회, 선택). "
                                             + "상점 아이템 목록 응답의 theme.id 또는 GET /api/v1/gacha 응답의 themeId 값")
                                     @RequestParam(required = false) Long themeId) {
        return shopQueryService.getItems(user.id(), themeId);
    }

    @Operation(summary = "아이템 구매",
            description = "아이템의 구매 재화(purchaseCurrencyType, 상점 판매 아이템은 DIAMOND)로 가격(priceAmount)만큼 차감하고 "
                    + "아이템을 보유 목록에 지급합니다. 차감과 지급은 하나의 트랜잭션으로 처리되며, "
                    + "응답에 지급된 보유 아이템 ID(userItemId)와 차감 후 지갑 잔액을 함께 내려줍니다. "
                    + "활성 상태이고 구매 재화·가격이 설정된 아이템만 구매할 수 있으며, 이미 보유한 아이템은 다시 구매할 수 없습니다. "
                    + "지급된 아이템은 즉시 인벤토리(GET /api/v1/me/items)에 나타나고 방 슬롯 배치(PUT /api/v1/rooms/me/slots)에 사용할 수 있습니다. "
                    + "itemId 는 상점 아이템 목록 조회 응답의 id 값을 사용합니다.")
    @PostMapping("/{itemId}/purchase")
    public PurchaseResponse purchase(@CurrentUser AuthUser user,
                                     @Parameter(description = "구매할 아이템 ID. GET /api/v1/items (상점 아이템 목록) 응답의 id 값")
                                     @PathVariable Long itemId) {
        return shopCommandService.purchase(user.id(), itemId);
    }
}
