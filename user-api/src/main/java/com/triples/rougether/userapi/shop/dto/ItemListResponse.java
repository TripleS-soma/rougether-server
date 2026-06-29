package com.triples.rougether.userapi.shop.dto;

import java.util.List;

// 목록 응답은 items 배열로 감싼다(공통 규약, api.md).
public record ItemListResponse(List<ItemResponse> items) {
}
