package com.triples.rougether.userapi.recommendation.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.recommendation.entity.RecommendationStatus;
import com.triples.rougether.domain.recommendation.entity.RoutineRecommendation;
import com.triples.rougether.domain.recommendation.repository.RoutineRecommendationRepository;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.userapi.recommendation.dto.RecommendationProposalResponse;
import com.triples.rougether.userapi.recommendation.error.RecommendationErrorCode;
import com.triples.rougether.userapi.routine.dto.RepeatDays;
import com.triples.rougether.userapi.routine.dto.RoutineResponse;
import com.triples.rougether.userapi.routine.dto.RoutineUpdateRequest;
import com.triples.rougether.userapi.routine.service.RoutineService;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

// 조정 추천 수락/무시(#329). 수락은 루틴 수정과 같은 경로(RoutineService.update)를 재사용해 시간버전 분기
// 규칙이 그대로 적용되고, 추천 상태 갱신과 한 트랜잭션으로 묶인다(update 는 REQUIRED 라 이 트랜잭션에 참여).
@Service
@RequiredArgsConstructor
@Transactional
public class RecommendationCommandService {

    private final RoutineRecommendationRepository recommendationRepository;
    private final RoutineRepository routineRepository;
    private final RoutineService routineService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RoutineResponse accept(Long userId, Long recommendationId) {
        RoutineRecommendation recommendation = findOwned(userId, recommendationId);
        Instant now = Instant.now(clock);
        if (recommendation.getStatus() != RecommendationStatus.ACTIVE) {
            throw new BusinessException(RecommendationErrorCode.RECOMMENDATION_ALREADY_HANDLED);
        }
        if (recommendation.isExpired(now)) {
            throw new BusinessException(RecommendationErrorCode.RECOMMENDATION_EXPIRED);
        }
        Routine current = routineRepository.findAliveByLineage(userId, recommendation.getOriginRoutineId())
                .orElseThrow(() -> new BusinessException(RecommendationErrorCode.RECOMMENDATION_ROUTINE_DELETED));
        // 생성 시점 대상 버전과 다르면 사용자가 먼저 스케줄을 수정한 것 — 근거가 무효라 덮어쓰지 않는다
        if (!current.getId().equals(recommendation.getRoutineId())) {
            throw new BusinessException(RecommendationErrorCode.RECOMMENDATION_STALE);
        }
        RecommendationProposalResponse proposal = objectMapper.readValue(
                recommendation.getProposalJson(), RecommendationProposalResponse.class);
        // 저장된 proposal 방어 검증 — WEEKLY 는 루틴 수정 검증에 daysOfWeek 필수 체크가 없어(스펙 비대칭),
        // 빈 요일을 그대로 적용하면 어떤 날짜에도 안 걸리는 죽은 루틴이 된다. 룰 엔진은 항상 1개 이상을 만든다.
        if (proposal.daysOfWeek() == null || proposal.daysOfWeek().isEmpty()) {
            throw new IllegalStateException("조정 추천 proposal 의 daysOfWeek 가 비어 있음: " + recommendation.getId());
        }
        // repeatType/repeatDays 만 바꾸는 요청. categoryId·scheduledTime·endsOn 은 "null = 해제" 규칙이라
        // 현재값을 그대로 실어 보존한다(houseMissionId 는 null = 기존 유지).
        RoutineUpdateRequest request = new RoutineUpdateRequest(null,
                current.getCategory() != null ? current.getCategory().getId() : null, null,
                proposal.repeatType(), new RepeatDays(proposal.daysOfWeek()),
                current.getScheduledTime(), null, current.getEndsOn(), null);
        RoutineResponse applied = routineService.update(userId, current.getId(), request);
        recommendation.accept(now, applied.id());
        return applied;
    }

    public void dismiss(Long userId, Long recommendationId) {
        RoutineRecommendation recommendation = findOwned(userId, recommendationId);
        Instant now = Instant.now(clock);
        if (recommendation.getStatus() != RecommendationStatus.ACTIVE) {
            throw new BusinessException(RecommendationErrorCode.RECOMMENDATION_ALREADY_HANDLED);
        }
        // 만료된 추천은 무시도 거부(#355) - accept 와 대칭. 만료는 상태 전이 없는 lazy 판정이라 여기서 막지
        // 않으면 기한 지난 추천이 DISMISSED 로 남아 admin 퍼널의 만료 집계·클라이언트 카드 동작과 어긋난다.
        if (recommendation.isExpired(now)) {
            throw new BusinessException(RecommendationErrorCode.RECOMMENDATION_EXPIRED);
        }
        recommendation.dismiss(now);
    }

    // 소유권 guard(타인 추천은 존재 여부와 무관하게 404) + 상태 전이 직렬화를 위한 locking read —
    // 동시 accept 가 둘 다 ACTIVE 를 읽고 이중 버전 분기하는 것을 행 락으로 막는다(repository 주석 참고)
    private RoutineRecommendation findOwned(Long userId, Long recommendationId) {
        return recommendationRepository.findForUpdateByIdAndUserId(recommendationId, userId)
                .orElseThrow(() -> new BusinessException(RecommendationErrorCode.RECOMMENDATION_NOT_FOUND));
    }
}
