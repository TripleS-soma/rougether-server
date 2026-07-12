package com.triples.rougether.batch.reminder;

import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.data.domain.PageRequest;

@RequiredArgsConstructor
class ReminderCandidateReader implements ItemReader<Routine> {

    private static final int PAGE_SIZE = 200;

    private final RoutineRepository routineRepository;
    private final RoutineStatus status;
    private final LocalTime scheduledTime;
    private final LocalDate date;
    private final RoutineLogStatus completedStatus;
    private final NotificationType notificationType;
    private final Instant dayStart;
    private final Instant dayEndExclusive;

    private Iterator<Routine> currentBatch = Collections.emptyIterator();
    private Long cursorId = 0L;
    private boolean exhausted = false;

    @Override
    public Routine read() {
        if (!currentBatch.hasNext() && !exhausted) {
            List<Routine> batch = fetchNextBatch();
            if (batch.isEmpty()) {
                exhausted = true;
            } else {
                currentBatch = batch.iterator();
            }
        }
        if (!currentBatch.hasNext()) {
            return null;
        }
        Routine next = currentBatch.next();
        cursorId = next.getId();
        return next;
    }

    private List<Routine> fetchNextBatch() {
        return routineRepository.findReminderCandidates(status, scheduledTime, date, completedStatus,
                notificationType, dayStart, dayEndExclusive, cursorId, PageRequest.of(0, PAGE_SIZE));
    }
}
