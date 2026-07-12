package com.triples.rougether.batch.reminder;

import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

// routineReminderJob: Step1(판정·적재) -> Step2(발송)
@Configuration
class RoutineReminderJobConfig {

    static final String JOB_NAME = "routineReminderJob";
    static final String TARGET_MINUTE_PARAM = "targetMinute";
    static final DateTimeFormatter TARGET_MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int CHUNK_SIZE = 200;
    private static final int SKIP_LIMIT = 50;

    @Bean
    Job routineReminderJob(JobRepository jobRepository, Step routineReminderJudgeAndStageStep,
            Step routineReminderPushStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(routineReminderJudgeAndStageStep)
                .next(routineReminderPushStep)
                .build();
    }

    @Bean
    Step routineReminderJudgeAndStageStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            ReminderCandidateReader reminderCandidateReader,
            ReminderNotificationProcessor reminderNotificationProcessor,
            NotificationRepository notificationRepository) {
        return new StepBuilder("routineReminderJudgeAndStageStep", jobRepository)
                .<Routine, Notification>chunk(CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(reminderCandidateReader)
                .processor(reminderNotificationProcessor)
                .writer(chunk -> notificationRepository.saveAll(chunk.getItems()))
                .faultTolerant()
                .skip(Exception.class)
                .skipLimit(SKIP_LIMIT)
                .build();
    }

    @Bean
    Step routineReminderPushStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            ReminderPendingReader pendingReminderReader, ReminderPushWriter reminderPushWriter) {
        return new StepBuilder("routineReminderPushStep", jobRepository)
                .<Notification, Notification>chunk(CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(pendingReminderReader)
                .writer(reminderPushWriter)
                .build();
    }

    @Bean
    @StepScope
    ReminderCandidateReader reminderCandidateReader(RoutineRepository routineRepository,
            @Value("#{jobParameters['" + TARGET_MINUTE_PARAM + "']}") String targetMinuteParam) {
        LocalDateTime targetMinute = LocalDateTime.parse(targetMinuteParam, TARGET_MINUTE_FORMAT);
        LocalDate date = targetMinute.toLocalDate();
        LocalTime time = targetMinute.toLocalTime();
        Instant dayStart = date.atStartOfDay(KST).toInstant();
        Instant dayEndExclusive = date.plusDays(1).atStartOfDay(KST).toInstant();

        return new ReminderCandidateReader(routineRepository, RoutineStatus.ACTIVE, time, date,
                RoutineLogStatus.COMPLETED, NotificationType.ROUTINE_REMINDER, dayStart, dayEndExclusive);
    }

    @Bean
    @StepScope
    ReminderNotificationProcessor reminderNotificationProcessor(
            @Value("#{jobParameters['" + TARGET_MINUTE_PARAM + "']}") String targetMinuteParam) {
        LocalDate date = LocalDateTime.parse(targetMinuteParam, TARGET_MINUTE_FORMAT).toLocalDate();
        return new ReminderNotificationProcessor(date);
    }

    @Bean
    @StepScope
    ReminderPendingReader pendingReminderReader(NotificationRepository notificationRepository) {
        return new ReminderPendingReader(notificationRepository);
    }
}
