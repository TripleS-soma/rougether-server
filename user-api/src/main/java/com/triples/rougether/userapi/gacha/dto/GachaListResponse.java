package com.triples.rougether.userapi.gacha.dto;

import java.util.List;

// GET /api/v1/gacha 응답. 운영 중인 뽑기 머신 목록.
public record GachaListResponse(List<GachaResponse> items) {
}
