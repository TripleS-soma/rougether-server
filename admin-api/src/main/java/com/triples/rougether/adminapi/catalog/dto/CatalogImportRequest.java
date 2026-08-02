package com.triples.rougether.adminapi.catalog.dto;

import java.util.List;

// 카탈로그 일괄 적재 요청. convert 스크립트가 만든 V1 JSON 을 그대로 받는다.
public record CatalogImportRequest(
        List<ThemeDto> themes,
        List<CharacterDto> characters,
        List<ItemDto> items) {

    public record ThemeDto(String code, String name, boolean active) {
    }

    public record CharacterDto(String code, String name, String baseAssetKey,
                               int sortOrder, boolean active) {
    }

    // placementType 은 문자열(positioned / surface_slot / character) 그대로 사용한다.
    // character 는 뽑기 전용으로 구매 정보를 비우고, 나머지는 서버에서 DIAMOND 로 고정한다.
    public record ItemDto(String themeCode, String categoryCode, String placementType,
                          String surfaceSlotType, String characterSlotType, String name,
                          Integer priceAmount, String assetKey, boolean limited, boolean active) {
    }
}
