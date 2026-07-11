package com.triples.rougether.userapi.routine.reminder;

import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.userapi.agenda.DailyAgendaAssembler;
import com.triples.rougether.userapi.notification.service.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 루틴 리마인드 스케줄러
@Slf4j
@Component
@RequiredArgsConstructor
public class RoutineReminderScheduler {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RoutineRepository routineRepository;
    private final RoutineLogRepository routineLogRepository;
    private final NotificationRepository notificationRepository;
    private final DailyAgendaAssembler agendaAssembler;
    private final NotificationService notificationService;
    private final Clock clock;

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Seoul")
    public void sendDueReminders() {
        LocalDate today = LocalDate.now(clock);
        // scheduled_time 은 분 단위라 현재 시각을 분으로 잘라 정확히 그 분의 루틴만 매칭
        LocalTime minute = LocalTime.now(clock).truncatedTo(ChronoUnit.MINUTES);

        List<Routine> candidates = routineRepository
                .findByStatusAndScheduledTimeAndDeletedAtIsNull(RoutineStatus.ACTIVE, minute);
        if (candidates.isEmpty()) {
            return;
        }

        Instant todayStart = today.atStartOfDay(KST).toInstant();
        Instant tomorrowStart = today.plusDays(1).atStartOfDay(KST).toInstant();

        for (Routine routine : candidates) {
            try {
                if (shouldRemind(routine, today, todayStart, tomorrowStart)) {
                    notificationService.send(routine.getUser().getId(), NotificationType.ROUTINE_REMINDER,
                            ReminderMessage.TITLE, ReminderMessage.body(routine.getTitle()), routine.getId());
                }
            } catch (Exception e) {
                // 개별 루틴 발송 실패가 나머지 루틴 발송을 막지 않도록 격리
                log.warn("루틴 리마인드 발송 실패 - routineId={}", routine.getId(), e);
            }
        }
    }

    private boolean shouldRemind(Routine routine, LocalDate today, Instant todayStart, Instant tomorrowStart) {
        // 반복 규칙·유효기간상 오늘 대상인지 (오늘 현황 판정 로직 재사용)
        if (!agendaAssembler.isRoutineTargetOn(routine, today)) {
            return false;
        }
        // 오늘 이미 완료한 루틴은 제외
        if (routineLogRepository.existsByRoutineIdAndRoutineDateAndStatus(
                routine.getId(), today, RoutineLogStatus.COMPLETED)) {
            return false;
        }
        // 오늘 이미 리마인드를 보냈으면 제외(중복 발송 방지)
        return !notificationRepository.existsByUserAndTypeAndRefIdSentBetween(
                routine.getUser().getId(), NotificationType.ROUTINE_REMINDER, routine.getId(),
                todayStart, tomorrowStart);
    }
}
