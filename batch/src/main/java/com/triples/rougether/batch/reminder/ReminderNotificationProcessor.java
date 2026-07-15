package com.triples.rougether.batch.reminder;

import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.routine.RoutineRecurrence;
import com.triples.rougether.domain.routine.entity.Routine;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.ItemProcessor;

// 반복규칙상 오늘 대상이 아니면 null 반환
@RequiredArgsConstructor
class ReminderNotificationProcessor implements ItemProcessor<Routine, Notification> {

    private final LocalDate targetDate;

    @Override
    public Notification process(Routine routine) {
        if (!RoutineRecurrence.isTargetOn(routine, targetDate)) {
            return null;
        }
        return Notification.create(routine.getUser(), NotificationType.ROUTINE_REMINDER,
                ReminderMessage.TITLE, ReminderMessage.body(routine.getTitle()), routine.getId());
    }
}
