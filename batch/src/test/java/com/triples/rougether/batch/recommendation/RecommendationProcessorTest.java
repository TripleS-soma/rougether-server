package com.triples.rougether.batch.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.batch.recommendation.RecommendationRuleEvaluator.Proposal;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.recommendation.RecommendationExperimentPolicy;
import com.triples.rougether.domain.recommendation.entity.RecommendationExperimentAssignment;
import com.triples.rougether.domain.recommendation.entity.RecommendationExperimentVariant;
import com.triples.rougether.domain.recommendation.repository.RecommendationExperimentAssignmentRepository;
import com.triples.rougether.domain.recommendation.repository.RecommendationExperimentEligibilityRepository;
import com.triples.rougether.domain.recommendation.repository.RoutineRecommendationRepository;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RecommendationProcessorTest {

    private static final long USER_ID = 42L;
    private static final LocalDate COHORT_WEEK = LocalDate.of(2030, 8, 4);

    private final RoutineRecommendationRepository recommendationRepository = mock(
            RoutineRecommendationRepository.class);
    private final RecommendationExperimentAssignmentRepository assignmentRepository = mock(
            RecommendationExperimentAssignmentRepository.class);
    private final RecommendationExperimentEligibilityRepository eligibilityRepository = mock(
            RecommendationExperimentEligibilityRepository.class);
    private final RoutineRepository routineRepository = mock(RoutineRepository.class);
    private final RoutineLogRepository routineLogRepository = mock(RoutineLogRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final RecommendationRuleEvaluator evaluator = mock(RecommendationRuleEvaluator.class);
    private final Clock clock = Clock.fixed(Instant.parse("2030-08-04T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    private User user;

    @BeforeEach
    void setUp() {
        user = User.signUp("processor@rougether.dev");
        when(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).thenReturn(Optional.of(user));
    }

    @Test
    void CONTROL은_동일한_룰로_적격성을_판정하되_추천은_반환하지_않는다() {
        stubAssignment(RecommendationExperimentVariant.CONTROL);
        stubProposal();

        RecommendationProcessingResult result = processor().process(USER_ID);

        assertThat(result.eligibility()).isNotNull();
        assertThat(result.recommendations()).isEmpty();
        verify(evaluator).evaluate(any(), any(), any());
    }

    @Test
    void TREATMENT는_기존_룰_평가_결과를_추천으로_반환한다() {
        stubAssignment(RecommendationExperimentVariant.TREATMENT);
        stubProposal();

        RecommendationProcessingResult result = processor().process(USER_ID);

        assertThat(result.eligibility()).isNotNull();
        assertThat(result.recommendations()).hasSize(1);
        assertThat(result.recommendations().getFirst().getUser()).isSameAs(user);
    }

    @Test
    void 룰_제안이_없으면_배정과_적격성을_기록하지_않는다() {
        Routine routine = mock(Routine.class);
        when(routineRepository.findByUserIdAndStatusAndDeletedAtIsNullOrderByScheduledTimeAscOriginRoutineIdAsc(
                USER_ID, RoutineStatus.ACTIVE)).thenReturn(List.of(routine));
        when(routineLogRepository.findLineageAliveLogsInPeriod(anyLong(), any(), any(), any()))
                .thenReturn(List.of());
        when(evaluator.evaluate(any(), any(), any())).thenReturn(List.of());

        RecommendationProcessingResult result = processor().process(USER_ID);

        assertThat(result).isNull();
        verify(assignmentRepository, never()).findByExperimentKeyAndUserId(any(), anyLong());
        verify(eligibilityRepository, never()).existsByAssignmentIdAndCohortWeekStart(anyLong(), any());
    }

    @Test
    void 활성_추천_상한을_채운_사용자는_적격성을_기록하지_않는다() {
        when(recommendationRepository.countByUserIdAndStatusAndExpiresAtAfter(anyLong(), any(), any()))
                .thenReturn((long) RecommendationPolicy.ACTIVE_CAP_PER_USER);

        RecommendationProcessingResult result = processor().process(USER_ID);

        assertThat(result).isNull();
        verify(assignmentRepository, never()).findByExperimentKeyAndUserId(any(), anyLong());
    }

    @Test
    void 쿨다운으로_추천이_제거된_사용자는_적격성을_기록하지_않는다() {
        stubProposal();
        when(recommendationRepository.findOriginRoutineIdsCreatedAfter(anyLong(), any())).thenReturn(List.of(10L));

        RecommendationProcessingResult result = processor().process(USER_ID);

        assertThat(result).isNull();
        verify(assignmentRepository, never()).findByExperimentKeyAndUserId(any(), anyLong());
    }

    private void stubProposal() {
        Routine routine = mock(Routine.class);
        when(routineRepository.findByUserIdAndStatusAndDeletedAtIsNullOrderByScheduledTimeAscOriginRoutineIdAsc(
                USER_ID, RoutineStatus.ACTIVE)).thenReturn(List.of(routine));
        when(routineLogRepository.findLineageAliveLogsInPeriod(anyLong(), any(), any(), any()))
                .thenReturn(List.of());
        when(evaluator.evaluate(any(), any(), any())).thenReturn(List.of(
                new Proposal(10L, 11L, "WEEKLY", List.of("MON"), "월요일에 집중해 보세요.")));
        when(recommendationRepository.findOriginRoutineIdsCreatedAfter(anyLong(), any())).thenReturn(List.of());
    }

    private void stubAssignment(RecommendationExperimentVariant variant) {
        RecommendationExperimentAssignment assignment = RecommendationExperimentAssignment.assign(
                user, RecommendationExperimentPolicy.ROUTINE_ADJUSTMENT_V1, variant);
        ReflectionTestUtils.setField(assignment, "id", 7L);
        when(assignmentRepository.findByExperimentKeyAndUserId(
                RecommendationExperimentPolicy.ROUTINE_ADJUSTMENT_V1, USER_ID))
                .thenReturn(Optional.of(assignment));
        when(eligibilityRepository.existsByAssignmentIdAndCohortWeekStart(7L, COHORT_WEEK)).thenReturn(false);
    }

    private RecommendationProcessor processor() {
        return new RecommendationProcessor(recommendationRepository, assignmentRepository, eligibilityRepository,
                routineRepository, routineLogRepository, userRepository, evaluator, clock,
                COHORT_WEEK, COHORT_WEEK.minusWeeks(3), COHORT_WEEK.minusWeeks(1), List.of());
    }
}
