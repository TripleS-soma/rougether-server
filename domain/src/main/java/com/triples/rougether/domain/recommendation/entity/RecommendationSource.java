package com.triples.rougether.domain.recommendation.entity;

// 추천 생성 주체. MVP 는 룰 엔진 1종(LLM 미사용, #329). WEEKLY_LLM(주간 회고 LLM 연계)은 후속 예약이라 값 미보유.
public enum RecommendationSource {
    RULE
}
