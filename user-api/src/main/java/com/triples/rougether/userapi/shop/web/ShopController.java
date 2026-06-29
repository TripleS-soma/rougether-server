package com.triples.rougether.userapi.shop.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.shop.dto.ItemListResponse;
import com.triples.rougether.userapi.shop.service.ShopQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 상점 아이템 목록. 인증된 사용자 기준(owned 판정), themeId 로 테마 필터.
@RestController
@RequestMapping("/api/v1/items")
public class ShopController {

    private final ShopQueryService shopQueryService;

    public ShopController(ShopQueryService shopQueryService) {
        this.shopQueryService = shopQueryService;
    }

    @GetMapping
    public ItemListResponse getItems(@CurrentUser AuthUser user,
                                     @RequestParam(required = false) Long themeId) {
        return shopQueryService.getItems(user.id(), themeId);
    }
}
