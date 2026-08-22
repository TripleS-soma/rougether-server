package com.triples.rougether.userapi.recommendation.service;

import com.triples.rougether.domain.recommendation.entity.RecommendationStatus;
import com.triples.rougether.domain.recommendation.entity.RoutineRecommendation;
import com.triples.rougether.domain.recommendation.repository.RoutineRecommendationRepository;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.userapi.recommendation.dto.RecommendationItem;
import com.triples.rougether.userapi.recommendation.dto.RecommendationListResponse;
import com.triples.rougether.userapi.recommendation.dto.RecommendationProposalResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

// 내 활성 조정 추천 목록(#329). 만료·루틴 삭제·stale(생성 뒤 스케줄 선수정)은 상태 전이 없이 여기서 lazy 로
// 걸러 낸다 — ACTIVE 인 채 조용히 빠진 건은 지표에서 무반응 종결로 집계된다.
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationQueryService {

    private final RoutineRecommendationRepository recommendationRepository;
    private final RoutineRepository routineRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RecommendationListResponse getMyRecommendations(Long userId) {
        Instant now = Instant.now(clock);
        List<RoutineRecommendation> actives = recommendationRepository
                .findByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDescIdDesc(
                        userId, RecommendationStatus.ACTIVE, now);
        List<RecommendationItem> items = new ArrayList<>(actives.size());
        for (RoutineRecommendation recommendation : actives) {
            // 활성 추천은 사용자당 최대 3건이라 계보별 단건 조회로 충분함(N+1 아님)
            Routine current = routineRepository
                    .findAliveByLineage(userId, recommendation.getOriginRoutineId())
                    .orElse(null);
            if (current == null || !current.getId().equals(recommendation.getRoutineId())) {
                continue;
            }
            RecommendationProposalResponse proposal = readProposal(recommendation);
            if (proposal == null) {
                continue;
            }
            items.add(new RecommendationItem(recommendation.getId(), recommendation.getRecType(),
                    recommendation.getMessage(), current.getId(), recommendation.getOriginRoutineId(),
                    current.getTitle(), proposal, recommendation.getCreatedAt(),
                    recommendation.getExpiresAt()));
        }
        return new RecommendationListResponse(List.copyOf(items));
    }

    // 저장된 proposal 이 깨졌으면 그 건만 스킵한다(RepeatDays.fromJson 과 같은 정책) — 목록 전체를 막지 않음
    private RecommendationProposalResponse readProposal(RoutineRecommendation recommendation) {
        try {
            return objectMapper.readValue(recommendation.getProposalJson(), RecommendationProposalResponse.class);
        } catch (RuntimeException e) {
            log.warn("조정 추천 proposal 역직렬화 실패 → 목록에서 제외 - recommendationId={}", recommendation.getId(), e);
            return null;
        }
    }
}
