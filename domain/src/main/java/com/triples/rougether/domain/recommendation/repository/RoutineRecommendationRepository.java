package com.triples.rougether.domain.recommendation.repository;

import com.triples.rougether.domain.recommendation.entity.RecommendationStatus;
import com.triples.rougether.domain.recommendation.entity.RoutineRecommendation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoutineRecommendationRepository extends JpaRepository<RoutineRecommendation, Long> {

    // 활성 목록: ACTIVE·미만료를 최신 생성순으로. 계보 현재 버전 대조(삭제·stale 제외)는 호출자(user-api) 몫.
    List<RoutineRecommendation> findByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDescIdDesc(
            Long userId, RecommendationStatus status, Instant now);

    // 소유권 guard: 타인 추천은 존재 여부와 무관하게 empty(→404)
    Optional<RoutineRecommendation> findByIdAndUserId(Long id, Long userId);

    // 재추천 쿨다운(#329): cutoff(now-14일) 이후 생성된 추천의 계보 키 — 상태 무관(무시한 제안을 곧바로 다시 만들지 않음)
    @Query("select r.originRoutineId from RoutineRecommendation r "
            + "where r.user.id = :userId and r.createdAt > :cutoff")
    List<Long> findOriginRoutineIdsCreatedAfter(@Param("userId") Long userId, @Param("cutoff") Instant cutoff);

    // 사용자당 활성 상한(3건) 예산 계산용: ACTIVE·미만료 건수
    long countByUserIdAndStatusAndExpiresAtAfter(Long userId, RecommendationStatus status, Instant now);
}
