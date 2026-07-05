package com.triples.rougether.userapi.shop.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.shop.dto.MyItemListResponse;
import com.triples.rougether.userapi.shop.service.ShopQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 인벤토리. 방 꾸미기 화면이 보유 아이템 배치에 사용.
@Tag(name = "Shop", description = "상점 관련 API")
@RestController
@RequestMapping("/api/v1/me/items")
public class MyItemController {

    private final ShopQueryService shopQueryService;

    public MyItemController(ShopQueryService shopQueryService) {
        this.shopQueryService = shopQueryService;
    }

    @Operation(summary = "인벤토리 조회",
            description = "로그인한 회원이 보유한 아이템 목록을 최근 획득 순으로 반환합니다. categoryCode 로 필터링할 수 있습니다.")
    @GetMapping
    public MyItemListResponse getMyItems(@CurrentUser AuthUser user,
                                         @Parameter(description = "카테고리 코드 필터 (선택)")
                                         @RequestParam(required = false) String categoryCode) {
        return shopQueryService.getMyItems(user.id(), categoryCode);
    }
}
