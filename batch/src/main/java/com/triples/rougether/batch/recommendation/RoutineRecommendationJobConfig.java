package com.triples.rougether.batch.recommendation;

import com.triples.rougether.batch.recommendation.RecommendationRuleEvaluator.WeekRange;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.recommendation.entity.RecommendationExperimentAssignment;
import com.triples.rougether.domain.recommendation.entity.RecommendationExperimentEligibility;
import com.triples.rougether.domain.recommendation.entity.RoutineRecommendation;
import com.triples.rougether.domain.recommendation.repository.RecommendationExperimentAssignmentRepository;
import com.triples.rougether.domain.recommendation.repository.RecommendationExperimentEligibilityRepository;
import com.triples.rougether.domain.recommendation.repository.RoutineRecommendationRepository;
import com.triples.rougether.domain.report.WeeklyReportPolicy;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.DefaultJobParametersValidator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.skip.LimitCheckingItemSkipPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

// 조정 추천 생성 job(#329). weekStart(대상 주 일요일) 파라미터 하나로 인스턴스가 식별돼 같은 주는 한 번만
// COMPLETED 된다. 근거 창은 weekStart 를 마지막 주로 하는 최근 3주. LLM·외부 호출이 없는 순수 DB 작업이라
// chunk 를 회고(1)보다 크게 잡고, 사용자 단위 예외는 skip 으로 격리한다.
@Configuration
class RoutineRecommendationJobConfig {

    static final String JOB_NAME = "routineRecommendationJob";
    static final String WEEK_START_PARAM = "weekStart";
    private static final int CHUNK_SIZE = 10;
    private static final int SKIP_LIMIT = 50;

    @Bean
    Job routineRecommendationJob(JobRepository jobRepository, Step routineRecommendationStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                // weekStart 누락 시 StepScope 빈에서 LocalDate.parse(null) 로 죽는 대신 시작 전에 거른다. run 은 테스트용 재실행 키.
                .validator(new DefaultJobParametersValidator(new String[] {WEEK_START_PARAM}, new String[] {"run"}))
                .start(routineRecommendationStep)
                .build();
    }

    @Bean
    Step routineRecommendationStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            RecommendationUserReader recommendationUserReader, RecommendationProcessor recommendationProcessor,
            RecommendationExperimentAssignmentRepository assignmentRepository,
            RecommendationExperimentEligibilityRepository eligibilityRepository,
            RoutineRecommendationRepository routineRecommendationRepository) {
        return new StepBuilder("routineRecommendationStep", jobRepository)
                .<Long, RecommendationProcessingResult>chunk(CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(recommendationUserReader)
                .processor(recommendationProcessor)
                .writer(chunk -> {
                    List<RecommendationExperimentAssignment> assignments = chunk.getItems().stream()
                            .map(RecommendationProcessingResult::newAssignment)
                            .filter(Objects::nonNull)
                            .toList();
                    assignmentRepository.saveAll(assignments);
                    List<RecommendationExperimentEligibility> eligibilities = chunk.getItems().stream()
                            .map(RecommendationProcessingResult::eligibility)
                            .filter(Objects::nonNull)
                            .toList();
                    eligibilityRepository.saveAll(eligibilities);
                    List<RoutineRecommendation> recommendations = chunk.getItems().stream()
                            .flatMap(result -> result.recommendations().stream())
                            .toList();
                    routineRecommendationRepository.saveAll(recommendations);
                })
                .faultTolerant()
                // 사용자 단위 예외(집계 오류 등)는 그 사용자만 건너뛰고 job 은 계속 간다(상한 SKIP_LIMIT)
                .skipPolicy(new LimitCheckingItemSkipPolicy(SKIP_LIMIT, Map.of(Exception.class, true)))
                .skipListener(new RecommendationSkipLogger())
                .build();
    }

    @Bean
    @StepScope
    RecommendationUserReader recommendationUserReader(RoutineLogRepository routineLogRepository,
            @Value("#{jobParameters['" + WEEK_START_PARAM + "']}") String weekStartParam) {
        LocalDate weekStart = LocalDate.parse(weekStartParam);
        return new RecommendationUserReader(routineLogRepository,
                RecommendationPolicy.windowStart(weekStart), WeeklyReportPolicy.weekEndOf(weekStart));
    }

    @Bean
    @StepScope
    RecommendationProcessor recommendationProcessor(RoutineRecommendationRepository routineRecommendationRepository,
            RecommendationExperimentAssignmentRepository assignmentRepository,
            RecommendationExperimentEligibilityRepository eligibilityRepository,
            RoutineRepository routineRepository, RoutineLogRepository routineLogRepository,
            UserRepository userRepository, RecommendationRuleEvaluator recommendationRuleEvaluator, Clock clock,
            @Value("#{jobParameters['" + WEEK_START_PARAM + "']}") String weekStartParam,
            @Value("${recommendation.experiment.holdout-enabled:true}") boolean holdoutEnabled) {
        LocalDate weekStart = LocalDate.parse(weekStartParam);
        return new RecommendationProcessor(routineRecommendationRepository, assignmentRepository,
                eligibilityRepository, routineRepository, routineLogRepository, userRepository,
                recommendationRuleEvaluator, clock, weekStart.plusWeeks(1),
                RecommendationPolicy.windowStart(weekStart), WeeklyReportPolicy.weekEndOf(weekStart),
                weeksOf(weekStart), holdoutEnabled);
    }

    // 근거 창의 일~토 주 3개(오래된 주 → 대상 주). 룰 2의 "직전 2주" = 마지막 2개
    static List<WeekRange> weeksOf(LocalDate weekStart) {
        List<WeekRange> weeks = new ArrayList<>(RecommendationPolicy.WINDOW_WEEKS);
        for (int i = RecommendationPolicy.WINDOW_WEEKS - 1; i >= 0; i--) {
            LocalDate start = weekStart.minusWeeks(i);
            weeks.add(new WeekRange(start, WeeklyReportPolicy.weekEndOf(start)));
        }
        return List.copyOf(weeks);
    }
}
