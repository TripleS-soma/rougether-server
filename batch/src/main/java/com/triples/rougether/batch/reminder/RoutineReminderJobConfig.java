package com.triples.rougether.batch.reminder;

import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
import com.triples.rougether.domain.member.repository.UserRepository;

// reminderJob: Step1(루틴 판정·적재) -> Step2(투두 판정·적재) -> Step3(발송)
@Configuration
class RoutineReminderJobConfig {

    static final String JOB_NAME = "reminderJob";
    static final String TARGET_MINUTE_PARAM = "targetMinute";
    static final DateTimeFormatter TARGET_MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int CHUNK_SIZE = 200;
    private static final int SKIP_LIMIT = 50;

    @Bean
    Job reminderJob(JobRepository jobRepository, Step routineReminderJudgeAndStageStep,
            Step todoReminderJudgeAndStageStep, Step reminderPushStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(routineReminderJudgeAndStageStep)
                .next(todoReminderJudgeAndStageStep)
                .next(reminderPushStep)
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
    Step todoReminderJudgeAndStageStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            TodoReminderCandidateReader todoReminderCandidateReader,
            NotificationRepository notificationRepository) {
        return new StepBuilder("todoReminderJudgeAndStageStep", jobRepository)
                .<Todo, Notification>chunk(CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(todoReminderCandidateReader)
                .processor(todo -> Notification.create(todo.getUser(), NotificationType.TODO_REMINDER,
                        ReminderMessage.todoTitle(todo.getUser().getLanguage()), ReminderMessage.todoBody(todo.getTitle(), todo.getUser().getLanguage()), todo.getId()))
                .writer(chunk -> notificationRepository.saveAll(chunk.getItems()))
                .faultTolerant()
                .skip(Exception.class)
                .skipLimit(SKIP_LIMIT)
                .build();
    }

    @Bean
    Step reminderPushStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            ReminderPendingReader pendingReminderReader, ReminderPushWriter reminderPushWriter) {
        return new StepBuilder("reminderPushStep", jobRepository)
                .<Notification, Notification>chunk(CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(pendingReminderReader)
                .writer(reminderPushWriter)
                .build();
    }

    @Bean
    @StepScope
    ReminderCandidateReader reminderCandidateReader(RoutineRepository routineRepository,
            UserRepository users,
            @Value("#{jobParameters['" + TARGET_MINUTE_PARAM + "']}") String targetMinuteParam) {
        Instant instant = LocalDateTime.parse(targetMinuteParam, TARGET_MINUTE_FORMAT).atZone(KST).toInstant();
        return new ReminderCandidateReader(routineRepository, instant, users.findActiveTimeZones());
    }

    @Bean
    @StepScope
    ReminderNotificationProcessor reminderNotificationProcessor(
            @Value("#{jobParameters['" + TARGET_MINUTE_PARAM + "']}") String targetMinuteParam) {
        Instant instant = LocalDateTime.parse(targetMinuteParam, TARGET_MINUTE_FORMAT).atZone(KST).toInstant();
        return new ReminderNotificationProcessor(instant);
    }

    @Bean
    @StepScope
    TodoReminderCandidateReader todoReminderCandidateReader(TodoRepository todoRepository,
            UserRepository users,
            @Value("#{jobParameters['" + TARGET_MINUTE_PARAM + "']}") String targetMinuteParam) {
        Instant instant = LocalDateTime.parse(targetMinuteParam, TARGET_MINUTE_FORMAT).atZone(KST).toInstant();
        return new TodoReminderCandidateReader(todoRepository, instant, users.findActiveTimeZones());
    }

    @Bean
    @StepScope
    ReminderPendingReader pendingReminderReader(NotificationRepository notificationRepository) {
        return new ReminderPendingReader(notificationRepository,
                List.of(NotificationType.ROUTINE_REMINDER, NotificationType.TODO_REMINDER));
    }
}
