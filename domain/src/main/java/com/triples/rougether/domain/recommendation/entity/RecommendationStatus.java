package com.triples.rougether.domain.recommendation.entity;

// 조정 추천 상태. 만료는 상태값이 아니라 expires_at 경과로 lazy 판정한다(#329) — ACTIVE 인 채 만료되면 무반응 종결.
public enum RecommendationStatus {
    ACTIVE,
    ACCEPTED,
    DISMISSED
}
