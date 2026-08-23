package com.triples.rougether.domain.recommendation.repository;

import com.triples.rougether.domain.recommendation.entity.RecommendationStatus;
import java.time.Instant;

// 관리자 추천 퍼널 관측(#332)용 projection. 추천 메시지·제안 본문은 싣지 않고 상태·시각·계보 키만 전달함.
// 주 버킷·만료 판정·수락 효과 델타 계산은 admin-api 서비스가 함.
public interface RecommendationFunnelRow {

    Long getUserId();

    Long getOriginRoutineId();

    RecommendationStatus getStatus();

    Instant getCreatedAt();

    Instant getActedAt();

    Instant getExpiresAt();
}
