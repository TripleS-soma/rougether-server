package com.triples.rougether.adminapi.catalog.dto;

import java.util.List;

// 목록 응답은 items 배열로 감싼다 (spec api.md 공통 계약, ItemSlotListResponse 등과 동일).
public record CatalogCharacterListResponse(List<CatalogCharacterRow> items) {
}
