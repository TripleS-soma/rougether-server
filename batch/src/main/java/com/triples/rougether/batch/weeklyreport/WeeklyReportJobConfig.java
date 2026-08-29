package com.triples.rougether.batch.weeklyreport;

import com.triples.rougether.domain.goal.repository.UserGoalRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.recommendation.repository.RoutineRecommendationRepository;
import com.triples.rougether.domain.report.WeeklyReportPolicy;
import com.triples.rougether.domain.report.entity.WeeklyReport;
import com.triples.rougether.domain.report.repository.WeeklyReportRepository;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.infra.llm.LlmAuthException;
import com.triples.rougether.infra.llm.LlmClient;
import com.triples.rougether.infra.llm.LlmProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
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

// 주간 회고 생성 job(#286). weekStart(일요일) 파라미터 하나로 인스턴스가 식별돼 같은 주는 한 번만 COMPLETED 된다.
// chunk=1: 사용자 1명 = 트랜잭션 1개. LLM 호출(수 초~수십 초)이 트랜잭션 안에서 일어나므로 커넥션 점유를 사용자 1명
// 분량으로 묶고, 실패한 사용자만 skip 되게 한다(다른 사용자의 LLM 결과가 롤백으로 버려지지 않음).
@Configuration
class WeeklyReportJobConfig {

    static final String JOB_NAME = "weeklyReportJob";
    static final String WEEK_START_PARAM = "weekStart";
    static final int WEEK_LENGTH_DAYS = WeeklyReportPolicy.WEEK_LENGTH_DAYS;
    private static final int CHUNK_SIZE = 1;
    private static final int SKIP_LIMIT = 50;

    @Bean
    Job weeklyReportJob(JobRepository jobRepository, Step weeklyReportStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                // weekStart 누락 시 StepScope 빈에서 LocalDate.parse(null) 로 죽는 대신 시작 전에 거른다. run 은 테스트용 재실행 키.
                .validator(new DefaultJobParametersValidator(new String[] {WEEK_START_PARAM}, new String[] {"run"}))
                .start(weeklyReportStep)
                .build();
    }

    @Bean
    Step weeklyReportStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            WeeklyReportUserReader weeklyReportUserReader, WeeklyReportProcessor weeklyReportProcessor,
            WeeklyReportRepository weeklyReportRepository) {
        return new StepBuilder("weeklyReportStep", jobRepository)
                .<Long, WeeklyReport>chunk(CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(weeklyReportUserReader)
                .processor(weeklyReportProcessor)
                .writer(chunk -> weeklyReportRepository.saveAll(chunk.getItems()))
                .faultTolerant()
                // 사용자 단위 예외(집계 오류·unique 충돌 등)는 그 사용자만 건너뛰고 job 은 계속 간다(상한 SKIP_LIMIT).
                // LLM 일시 실패는 processor 가 FALLBACK 으로 흡수하므로 여기 오지 않는다.
                // 단 LLM 인증 실패(401/403)는 사용자 단위 문제가 아니라 설정 장애 — skip 하지 않고 job 을 FAILED 로 끝내
                // 키를 고친 뒤 다음 트리거가 같은 weekStart 로 재시작하게 한다(이미 저장된 사용자는 exists 가드로 통과).
                .skipPolicy(new LimitCheckingItemSkipPolicy(SKIP_LIMIT,
                        Map.of(Exception.class, true, LlmAuthException.class, false)))
                // skip 된 사용자는 그 주 회고가 영구 미생성이므로 어떤 userId 가 왜 빠졌는지 반드시 남긴다
                .skipListener(new WeeklyReportSkipLogger())
                .build();
    }

    @Bean
    @StepScope
    WeeklyReportUserReader weeklyReportUserReader(RoutineLogRepository routineLogRepository,
            @Value("#{jobParameters['" + WEEK_START_PARAM + "']}") String weekStartParam) {
        LocalDate weekStart = LocalDate.parse(weekStartParam);
        return new WeeklyReportUserReader(routineLogRepository, weekStart, weekEndOf(weekStart));
    }

    @Bean
    @StepScope
    WeeklyReportProcessor weeklyReportProcessor(WeeklyReportRepository weeklyReportRepository,
            RoutineLogRepository routineLogRepository, StreakRepository streakRepository,
            UserRepository userRepository, UserGoalRepository userGoalRepository,
            RoutineRepository routineRepository,
            RoutineRecommendationRepository routineRecommendationRepository,
            WeeklyStatsAggregator aggregator, WeeklyReportPromptBuilder promptBuilder, WeeklyReportParser parser,
            LlmClient llmClient, LlmProperties llmProperties, Clock clock,
            @Value("#{jobParameters['" + WEEK_START_PARAM + "']}") String weekStartParam) {
        LocalDate weekStart = LocalDate.parse(weekStartParam);
        return new WeeklyReportProcessor(weeklyReportRepository, routineLogRepository, streakRepository,
                userRepository, userGoalRepository, routineRepository, routineRecommendationRepository,
                aggregator, promptBuilder, parser, llmClient, llmProperties, clock, weekStart,
                weekEndOf(weekStart));
    }

    static LocalDate weekEndOf(LocalDate weekStart) {
        return WeeklyReportPolicy.weekEndOf(weekStart);
    }
}
