package com.triples.rougether.batch.appicon;

import com.triples.rougether.batch.reminder.ReminderPushWriter;
import com.triples.rougether.domain.appicon.AppIconPolicy;
import com.triples.rougether.domain.appicon.AppIconState;
import com.triples.rougether.domain.appicon.entity.UserAppActivity;
import com.triples.rougether.domain.appicon.repository.UserAppActivityRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.entity.PushStatus;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AppIconReminderService {

    private final UserRepository userRepository;
    private final UserAppActivityRepository activityRepository;
    private final NotificationRepository notificationRepository;
    private final StreakRepository streakRepository;
    private final RoutineLogRepository routineLogRepository;
    private final TodoRepository todoRepository;
    private final ReminderPushWriter pushWriter;
    private final Clock clock;

    // 활동 갱신·탈퇴·알림 적재/발송 모두 사용자 행부터 잠가 같은 순서로 직렬화함.
    // 알림 내역과 단계 high-water mark를 같은 트랜잭션에서 커밋함.
    public void stage(Long userId) {
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        Instant now = clock.instant();
        if (!isEligible(user) || !isDeliveryTime(now)) {
            return;
        }
        UserAppActivity activity = activityRepository.findById(userId).orElse(null);
        if (activity == null) {
            return;
        }
        AppIconState state = currentState(activity, now);
        if (state.inactivityStage() <= activity.getLastNotifiedStage()) {
            return;
        }
        Notification notification = notificationRepository.save(Notification.create(
                user, NotificationType.APP_INACTIVITY_REMINDER,
                AppIconReminderMessage.title(state), AppIconReminderMessage.body(state), userId));
        activity.recordNotification(state, notification.getId());
    }

    // 적재 커밋 이후 별도 트랜잭션에서 기존 FCM writer를 재사용함.
    // 여러 worker가 같은 PENDING을 읽어도 사용자 잠금 뒤 최신 상태를 다시 읽어 중복 호출을 막음.
    public void sendPending(Long userId, Long notificationId) {
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        Notification notification = notificationRepository.findByIdAndUserId(notificationId, userId).orElse(null);
        if (notification == null || notification.getPushStatus() != PushStatus.PENDING
                || notification.getType() != NotificationType.APP_INACTIVITY_REMINDER) {
            return;
        }
        UserAppActivity activity = activityRepository.findById(userId).orElse(null);
        Instant now = clock.instant();
        if (!isEligible(user) || activity == null
                || !Objects.equals(activity.getLastNotificationId(), notificationId)
                || currentState(activity, now).inactivityStage() != activity.getLastNotifiedStage()
                || activity.getLastNotifiedStage() == 0) {
            notification.markPushBlocked();
            return;
        }
        // 처리 중 21시를 넘긴 PENDING은 다음 허용 시간까지 보류함.
        if (!isDeliveryTime(now)) {
            return;
        }
        pushWriter.write(new Chunk<>(notification));
    }

    private AppIconState currentState(UserAppActivity activity, Instant now) {
        Long userId = activity.getUserId();
        LocalDate today = now.atZone(AppIconPolicy.KST).toLocalDate();
        int currentStreak = streakRepository.findByUserId(userId)
                .map(streak -> streak.currentCountOn(today)).orElse(0);
        boolean completedToday = routineLogRepository.countByRoutine_UserIdAndRoutineDateAndStatus(
                userId, today, RoutineLogStatus.COMPLETED) > 0
                || todoRepository.existsByUserIdAndStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(
                        userId, TodoStatus.COMPLETED,
                        today.atStartOfDay(AppIconPolicy.KST).toInstant(),
                        today.plusDays(1).atStartOfDay(AppIconPolicy.KST).toInstant());
        return AppIconPolicy.evaluate(activity.getLastForegroundAt(), currentStreak, completedToday, now);
    }

    static boolean isDeliveryTime(Instant now) {
        int hour = now.atZone(AppIconPolicy.KST).getHour();
        return hour >= 9 && hour < 21;
    }

    private boolean isEligible(User user) {
        return user != null && !user.isDeleted() && !user.isBot();
    }
}
