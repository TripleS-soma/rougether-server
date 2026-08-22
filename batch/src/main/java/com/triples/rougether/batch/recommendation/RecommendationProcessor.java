package com.triples.rougether.batch.recommendation;

import com.triples.rougether.batch.recommendation.RecommendationRuleEvaluator.Proposal;
import com.triples.rougether.batch.recommendation.RecommendationRuleEvaluator.WeekRange;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.recommendation.entity.RecommendationStatus;
import com.triples.rougether.domain.recommendation.entity.RecommendationType;
import com.triples.rougether.domain.recommendation.entity.RoutineRecommendation;
import com.triples.rougether.domain.recommendation.repository.RoutineRecommendationRepository;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// 사용자 1명 → 조정 추천 0~N건(#329). 룰 평가는 evaluator(순수)가 하고, 여기서는 상한·쿨다운을 적용해 엔티티로 만든다.
// 재실행·복구 시 중복 방지는 쿨다운(직전 14일 내 같은 계보 추천 생성 금지, 상태 무관)이 겸한다.
@RequiredArgsConstructor
class RecommendationProcessor implements ItemProcessor<Long, List<RoutineRecommendation>> {

    private final RoutineRecommendationRepository recommendationRepository;
    private final RoutineRepository routineRepository;
    private final RoutineLogRepository routineLogRepository;
    private final UserRepository userRepository;
    private final RecommendationRuleEvaluator evaluator;
    private final Clock clock;
    private final LocalDate windowStart;
    private final LocalDate windowEnd;
    private final List<WeekRange> weeks;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    // proposal JSON 스키마 — 루틴 repeat_days 와 같은 요일 토큰의 절대값 스케줄
    record ProposalPayload(String repeatType, List<String> daysOfWeek) {
    }

    @Override
    public List<RoutineRecommendation> process(Long userId) {
        Instant now = Instant.now(clock);
        long activeCount = recommendationRepository.countByUserIdAndStatusAndExpiresAtAfter(
                userId, RecommendationStatus.ACTIVE, now);
        int budget = RecommendationPolicy.ACTIVE_CAP_PER_USER - (int) activeCount;
        if (budget <= 0) {
            return null;
        }
        List<Routine> activeRoutines = routineRepository
                .findByUserIdAndStatusAndDeletedAtIsNullOrderByScheduledTimeAscOriginRoutineIdAsc(
                        userId, RoutineStatus.ACTIVE);
        if (activeRoutines.isEmpty()) {
            return null;
        }
        List<RoutineLog> logs = routineLogRepository.findLineageAliveLogsInPeriod(
                userId, windowStart, windowEnd, RecommendationPolicy.COUNTED_LOG_STATUSES);
        Map<Long, List<RoutineLog>> logsByLineage = logs.stream()
                .collect(Collectors.groupingBy(routineLog ->
                        RecommendationRuleEvaluator.lineageKey(routineLog.getRoutine())));
        List<Proposal> proposals = evaluator.evaluate(activeRoutines, logsByLineage, weeks);
        if (proposals.isEmpty()) {
            return null;
        }
        Set<Long> cooldownLineages = new HashSet<>(recommendationRepository.findOriginRoutineIdsCreatedAfter(
                userId, now.minus(RecommendationPolicy.LINEAGE_COOLDOWN)));
        User user = userRepository.getReferenceById(userId);
        Instant expiresAt = now.plus(RecommendationPolicy.TTL);
        List<RoutineRecommendation> recommendations = proposals.stream()
                .filter(proposal -> !cooldownLineages.contains(proposal.originRoutineId()))
                .limit(budget)
                .map(proposal -> RoutineRecommendation.rule(user, proposal.originRoutineId(), proposal.routineId(),
                        RecommendationType.ADJUST_DAYS, toJson(proposal), proposal.message(), expiresAt))
                .toList();
        return recommendations.isEmpty() ? null : recommendations;
    }

    private String toJson(Proposal proposal) {
        return objectMapper.writeValueAsString(new ProposalPayload(proposal.repeatType(), proposal.daysOfWeek()));
    }
}
