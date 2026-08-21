package com.triples.rougether.batch.weeklyreport;

import com.triples.rougether.batch.reminder.ReminderPendingReader;
import com.triples.rougether.batch.reminder.ReminderPushWriter;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.domain.report.entity.WeeklyReport;
import com.triples.rougether.domain.report.repository.WeeklyReportRepository;
import java.time.LocalDate;
import java.util.List;
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

// weeklyReportPushJob: Step1(그 주 회고 → 알림 적재) -> Step2(발송). reminderJob 과 같은 2단계 구조로,
// 발송 게이트·FCM·PushStatus 종결은 reminder 의 범용 reader/writer 를 재사용한다.
// 생성 job(weeklyReportJob, 일 00:30)과 분리된 별도 job 이라 발송 시각(일 20:00)을 따로 가져가며,
// weekStart 파라미터 하나로 인스턴스가 식별돼 같은 주는 한 번만 COMPLETED 된다.
@Configuration
class WeeklyReportPushJobConfig {

    static final String JOB_NAME = "weeklyReportPushJob";
    static final String WEEK_START_PARAM = "weekStart";
    private static final int CHUNK_SIZE = 200;
    private static final int SKIP_LIMIT = 50;

    @Bean
    Job weeklyReportPushJob(JobRepository jobRepository, Step weeklyReportPushStageStep,
            Step weeklyReportPushSendStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                // weekStart 누락 시 StepScope 빈에서 LocalDate.parse(null) 로 죽는 대신 시작 전에 거른다. run 은 테스트용 재실행 키.
                .validator(new DefaultJobParametersValidator(new String[] {WEEK_START_PARAM}, new String[] {"run"}))
                .start(weeklyReportPushStageStep)
                .next(weeklyReportPushSendStep)
                .build();
    }

    @Bean
    Step weeklyReportPushStageStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            WeeklyReportPushCandidateReader weeklyReportPushCandidateReader,
            WeeklyReportPushProcessor weeklyReportPushProcessor,
            NotificationRepository notificationRepository) {
        return new StepBuilder("weeklyReportPushStageStep", jobRepository)
                .<WeeklyReport, Notification>chunk(CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(weeklyReportPushCandidateReader)
                .processor(weeklyReportPushProcessor)
                .writer(chunk -> notificationRepository.saveAll(chunk.getItems()))
                .faultTolerant()
                .skip(Exception.class)
                .skipLimit(SKIP_LIMIT)
                .skipListener(new WeeklyReportPushSkipLogger())
                .build();
    }

    @Bean
    Step weeklyReportPushSendStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            ReminderPendingReader weeklyReportPendingReader, ReminderPushWriter reminderPushWriter) {
        return new StepBuilder("weeklyReportPushSendStep", jobRepository)
                .<Notification, Notification>chunk(CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(weeklyReportPendingReader)
                .writer(reminderPushWriter)
                .build();
    }

    @Bean
    @StepScope
    WeeklyReportPushCandidateReader weeklyReportPushCandidateReader(WeeklyReportRepository weeklyReportRepository,
            @Value("#{jobParameters['" + WEEK_START_PARAM + "']}") String weekStartParam) {
        return new WeeklyReportPushCandidateReader(weeklyReportRepository, LocalDate.parse(weekStartParam));
    }

    // reminder 쪽 pendingReminderReader 와 같은 클래스지만 WEEKLY_REPORT 타입만 읽는 별도 빈 - 이름으로 주입된다
    @Bean
    @StepScope
    ReminderPendingReader weeklyReportPendingReader(NotificationRepository notificationRepository) {
        return new ReminderPendingReader(notificationRepository, List.of(NotificationType.WEEKLY_REPORT));
    }
}
