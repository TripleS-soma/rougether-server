package com.triples.rougether.domain.recommendation.repository;

import com.triples.rougether.domain.recommendation.entity.RecommendationStatus;
import java.time.Instant;

// 관리자 추천 퍼널 관측(#332)용 projection. 추천 메시지·제안 본문은 싣지 않고 상태·시각·계보 키만 전달함.
// 주 버킷·만료 판정·수락 효과 델타 계산은 admin-api 서비스가 함.
public interface RecommendationFunnelRow {

    Long getUserId();

    Long getOriginRoutineId();

    // 생성 시점 대상 버전 - 계보 현재 버전과 다르면 stale(무효) 판정에 쓴다
    Long getRoutineId();

    RecommendationStatus getStatus();

    // 수락 적용으로 분기된 버전(효과 측정 조인 키). 수락 전이면 null
    Long getAppliedRoutineId();

    Instant getCreatedAt();

    Instant getActedAt();

    Instant getExpiresAt();
}
