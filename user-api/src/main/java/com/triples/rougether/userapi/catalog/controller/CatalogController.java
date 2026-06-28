package com.triples.rougether.userapi.catalog.controller;

import com.triples.rougether.domain.shop.entity.PlacementType;
import com.triples.rougether.userapi.catalog.dto.CharacterListResponse;
import com.triples.rougether.userapi.catalog.dto.ItemListResponse;
import com.triples.rougether.userapi.catalog.dto.ThemeListResponse;
import com.triples.rougether.userapi.catalog.service.CatalogService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;

    @Operation(summary = "활성 테마 목록 조회")
    @GetMapping("/themes")
    public ThemeListResponse themes() {
        return catalogService.getThemes();
    }

    @Operation(
            summary = "상점 아이템 목록 조회",
            description = "활성 테마에 속한 활성 아이템만 반환합니다. "
                    + "themeId로 테마를 좁히고, placementType=SURFACE는 방 꾸미기, "
                    + "placementType=CHARACTER는 캐릭터 악세사리 탭으로 사용합니다.")
    @GetMapping("/items")
    public ItemListResponse items(
            @CurrentUser AuthUser authUser,
            @Parameter(description = "테마 ID 필터")
            @RequestParam(required = false) Long themeId,
            @Parameter(description = "아이템 배치 타입 필터. SURFACE=방 꾸미기, CHARACTER=캐릭터 악세사리")
            @RequestParam(required = false) PlacementType placementType) {
        return catalogService.getItems(authUser.id(), themeId, placementType);
    }

    @Operation(summary = "활성 캐릭터 목록 조회")
    @GetMapping("/characters")
    public CharacterListResponse characters() {
        return catalogService.getCharacters();
    }
}
