package com.triples.rougether.batch.eveningdigest;

import com.triples.rougether.batch.reminder.ReminderPushWriter;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.digest.repository.DailyIncompleteDigestRepository;
import com.triples.rougether.domain.notification.digest.repository.DailyIncompleteDigestTargetRepository;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.DefaultJobParametersValidator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
class EveningDigestJobConfig {

    static final String JOB_NAME = "eveningDigestJob";
    static final String TARGET_DATE_PARAM = "targetDate";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int STAGE_CHUNK_SIZE = 1;
    private static final int PUSH_CHUNK_SIZE = 1;
    private static final int SKIP_LIMIT = 50;

    @Bean
    Job eveningDigestJob(JobRepository jobRepository, Step eveningDigestStageStep,
            Step eveningDigestPushStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(new DefaultJobParametersValidator(new String[] {TARGET_DATE_PARAM}, new String[] {"run"}))
                .start(eveningDigestStageStep)
                .next(eveningDigestPushStep)
                .build();
    }

    @Bean
    Step eveningDigestStageStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            EveningDigestUserReader eveningDigestUserReader,
            EveningDigestProcessor eveningDigestProcessor,
            EveningDigestStageWriter eveningDigestStageWriter) {
        return new StepBuilder("eveningDigestStageStep", jobRepository)
                .<User, EveningDigestDraft>chunk(STAGE_CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(eveningDigestUserReader)
                .processor(eveningDigestProcessor)
                .writer(eveningDigestStageWriter)
                .faultTolerant()
                .skip(Exception.class)
                .skipLimit(SKIP_LIMIT)
                .skipListener(new EveningDigestStageSkipLogger())
                .build();
    }

    @Bean
    Step eveningDigestPushStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            EveningDigestPendingReader eveningDigestPendingReader, EveningDigestPushWriter eveningDigestPushWriter) {
        return new StepBuilder("eveningDigestPushStep", jobRepository)
                .<Notification, Notification>chunk(PUSH_CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(eveningDigestPendingReader)
                .writer(eveningDigestPushWriter)
                .faultTolerant()
                .skip(Exception.class)
                .skipLimit(SKIP_LIMIT)
                .skipListener(new EveningDigestPushSkipLogger())
                .build();
    }

    @Bean
    @StepScope
    EveningDigestUserReader eveningDigestUserReader(UserRepository userRepository,
            @Value("#{jobParameters['" + TARGET_DATE_PARAM + "']}") String targetDateParam) {
        LocalDate targetDate = LocalDate.parse(targetDateParam);
        Instant dayEndExclusive = targetDate.plusDays(1).atStartOfDay(KST).toInstant();
        return new EveningDigestUserReader(userRepository, targetDate, dayEndExclusive);
    }

    @Bean
    @StepScope
    EveningDigestProcessor eveningDigestProcessor(RoutineRepository routineRepository,
            TodoRepository todoRepository,
            @Value("#{jobParameters['" + TARGET_DATE_PARAM + "']}") String targetDateParam) {
        return new EveningDigestProcessor(routineRepository, todoRepository, LocalDate.parse(targetDateParam));
    }

    @Bean
    EveningDigestStageWriter eveningDigestStageWriter(DailyIncompleteDigestRepository digestRepository,
            DailyIncompleteDigestTargetRepository targetRepository,
            NotificationRepository notificationRepository) {
        return new EveningDigestStageWriter(digestRepository, targetRepository, notificationRepository);
    }

    @Bean
    EveningDigestPushWriter eveningDigestPushWriter(ReminderPushWriter reminderPushWriter,
            DailyIncompleteDigestRepository digestRepository, Clock clock) {
        return new EveningDigestPushWriter(reminderPushWriter, digestRepository, clock);
    }

    @Bean
    @StepScope
    EveningDigestPendingReader eveningDigestPendingReader(NotificationRepository notificationRepository,
            Clock clock,
            @Value("#{jobParameters['" + TARGET_DATE_PARAM + "']}") String targetDateParam) {
        return new EveningDigestPendingReader(notificationRepository, LocalDate.parse(targetDateParam), clock);
    }
}
