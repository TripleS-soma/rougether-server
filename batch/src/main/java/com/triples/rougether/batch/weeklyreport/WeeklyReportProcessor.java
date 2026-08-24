package com.triples.rougether.batch.weeklyreport;

import com.triples.rougether.batch.weeklyreport.WeeklyReportPromptBuilder.AcceptedAdjustment;
import com.triples.rougether.domain.goal.entity.UserGoal;
import com.triples.rougether.domain.goal.repository.UserGoalRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.recommendation.entity.RecommendationStatus;
import com.triples.rougether.domain.recommendation.entity.RoutineRecommendation;
import com.triples.rougether.domain.recommendation.repository.RoutineRecommendationRepository;
import com.triples.rougether.domain.report.WeeklyReportSections;
import com.triples.rougether.domain.report.WeeklyReportStats;
import com.triples.rougether.domain.report.WeeklyReportStats.RoutineStat;
import com.triples.rougether.domain.report.entity.WeeklyReport;
import com.triples.rougether.domain.report.repository.WeeklyReportRepository;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.Streak;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.infra.llm.LlmAuthException;
import com.triples.rougether.infra.llm.LlmChatRequest;
import com.triples.rougether.infra.llm.LlmClient;
import com.triples.rougether.infra.llm.LlmException;
import com.triples.rougether.infra.llm.LlmProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// 사용자 1명 → WeeklyReport 1건. 집계 → LLM(1회 + 파싱 실패 시 1회 재요청) → 실패하면 FALLBACK(통계+고정 문구).
// 이미 그 주 회고가 있거나 집계할 로그가 없으면 null(=skip). 재생성은 없다(결정값).
// 닫힌 루프(#334): 대상 주에 수락된 조정 추천이 있으면 그 주 계보 성과와 함께 프롬프트에 알려준다.
@Slf4j
@RequiredArgsConstructor
class WeeklyReportProcessor implements ItemProcessor<Long, WeeklyReport> {

    static final String FALLBACK_SUMMARY_FORMAT = "이번 주 루틴 %d회 중 %d회를 완료했어요.";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final WeeklyReportRepository weeklyReportRepository;
    private final RoutineLogRepository routineLogRepository;
    private final StreakRepository streakRepository;
    private final UserRepository userRepository;
    private final UserGoalRepository userGoalRepository;
    private final RoutineRecommendationRepository routineRecommendationRepository;
    private final WeeklyStatsAggregator aggregator;
    private final WeeklyReportPromptBuilder promptBuilder;
    private final WeeklyReportParser parser;
    private final LlmClient llmClient;
    private final LlmProperties llmProperties;
    private final Clock clock;
    private final LocalDate weekStart;
    private final LocalDate weekEnd;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Override
    public WeeklyReport process(Long userId) {
        if (weeklyReportRepository.existsByUserIdAndWeekStartDate(userId, weekStart)) {
            log.debug("주간 회고 이미 존재 - userId={}, weekStart={}", userId, weekStart);
            return null;
        }
        List<RoutineLog> logs = routineLogRepository.findAllWithRoutineInPeriod(
                userId, weekStart, weekEnd, WeeklyReportUserReader.COUNTED_STATUSES);
        if (logs.isEmpty()) {
            return null;
        }
        Optional<Streak> streak = streakRepository.findByUserId(userId);
        WeeklyReportStats stats = aggregator.aggregate(logs, streak, LocalDate.now(clock));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("회고 대상 사용자 없음: " + userId));
        List<UserGoal> goals = userGoalRepository.findByUserIdWithGoalOrderBySortOrder(userId);
        LlmChatRequest request = promptBuilder.build(user.getNickname(), user.getBio(), goals,
                weekStart, weekEnd, stats, acceptedAdjustments(userId, stats));

        String statsJson = toJson(stats);
        Instant generatedAt = Instant.now(clock);
        Optional<WeeklyReportParser.Parsed> parsed = generate(userId, request);
        if (parsed.isPresent()) {
            return WeeklyReport.generated(user, weekStart, weekEnd, llmProperties.model(), statsJson,
                    parsed.get().summary(), toJson(parsed.get().sections()), generatedAt);
        }
        String fallbackSummary = FALLBACK_SUMMARY_FORMAT.formatted(stats.scheduledCount(), stats.completedCount());
        return WeeklyReport.fallback(user, weekStart, weekEnd, statsJson, fallbackSummary,
                toJson(WeeklyReportSections.empty()), generatedAt);
    }

    // 1차 호출 → 파싱 실패면 같은 요청 1회 재시도. LLM 자체 오류(재시도 소진 포함)나 2차 파싱 실패면 empty.
    private Optional<WeeklyReportParser.Parsed> generate(Long userId, LlmChatRequest request) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            String content;
            try {
                content = llmClient.complete(request);
            } catch (LlmAuthException e) {
                // 키/권한 문제는 전 사용자 공통 장애 — FALLBACK 으로 덮어 영구 정본을 만들지 않고 job 을 실패시켜
                // (noSkip) 키를 고친 뒤 다음 트리거의 재시작으로 회수한다. 이미 저장된 사용자는 exists 가드로 건너뛴다.
                throw e;
            } catch (LlmException e) {
                log.warn("주간 회고 LLM 호출 실패 → FALLBACK - userId={}, retryable={}, cause={}",
                        userId, e.isRetryable(), e.getMessage());
                return Optional.empty();
            }
            try {
                return Optional.of(parser.parse(content));
            } catch (WeeklyReportParser.InvalidResponseException e) {
                log.warn("주간 회고 LLM 응답 파싱 실패(시도 {}/2) - userId={}, cause={}", attempt, userId, e.getMessage());
            }
        }
        return Optional.empty();
    }

    // 닫힌 루프(#334): 대상 주에 수락된 추천을 그 주 계보 통계와 매칭해 프롬프트 재료로 만든다.
    // 그 주 계보 기록이 없거나 proposal JSON 이 깨진 건은 제외한다(지어낼 숫자 없음 - 기존 lazy 정책과 동일).
    private List<AcceptedAdjustment> acceptedAdjustments(Long userId, WeeklyReportStats stats) {
        List<RoutineRecommendation> accepted = routineRecommendationRepository
                .findByUserIdAndStatusAndActedAtGreaterThanEqualAndActedAtLessThan(
                        userId, RecommendationStatus.ACCEPTED,
                        weekStart.atStartOfDay(KST).toInstant(),
                        weekEnd.plusDays(1).atStartOfDay(KST).toInstant());
        if (accepted.isEmpty()) {
            return List.of();
        }
        Map<Long, RoutineStat> statsByLineage = new HashMap<>();
        for (RoutineStat stat : stats.byRoutine()) {
            statsByLineage.putIfAbsent(stat.lineageId(), stat);
        }
        List<AcceptedAdjustment> adjustments = new ArrayList<>();
        for (RoutineRecommendation recommendation : accepted) {
            RoutineStat stat = statsByLineage.get(recommendation.getOriginRoutineId());
            if (stat == null) {
                continue;
            }
            AcceptedProposal proposal = readProposal(recommendation);
            if (proposal == null) {
                continue;
            }
            adjustments.add(new AcceptedAdjustment(stat.title(), proposal.repeatType(),
                    proposal.daysOfWeek(), stat.completed(), stat.failed()));
        }
        return adjustments;
    }

    // proposal 스케줄 절대값. RecommendationProcessor 의 ProposalPayload 와 같은 형태(요일 토큰 "MON"...)
    private record AcceptedProposal(String repeatType, List<String> daysOfWeek) {
    }

    private AcceptedProposal readProposal(RoutineRecommendation recommendation) {
        try {
            return objectMapper.readValue(recommendation.getProposalJson(), AcceptedProposal.class);
        } catch (RuntimeException e) {
            log.warn("닫힌 루프 proposal 역직렬화 실패 → 프롬프트에서 제외 - recommendationId={}",
                    recommendation.getId(), e);
            return null;
        }
    }

    private String toJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }
}
