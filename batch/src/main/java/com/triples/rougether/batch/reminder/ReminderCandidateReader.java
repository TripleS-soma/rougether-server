package com.triples.rougether.batch.reminder;

import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import java.time.Instant;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.data.domain.PageRequest;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.RoutineStatus;

@RequiredArgsConstructor
class ReminderCandidateReader implements ItemReader<Routine> {
    private static final int PAGE_SIZE = 200;
    private final RoutineRepository routineRepository;
    private final Instant targetInstant;
    private final List<String> timeZones;
    private Iterator<Routine> currentBatch = Collections.emptyIterator();
    private int zoneIndex;
    private Long cursorId = 0L;

    @Override
    public Routine read() {
        while (!currentBatch.hasNext()) {
            if (zoneIndex >= timeZones.size()) { return null; }
            var window = ReminderWindow.at(targetInstant, timeZones.get(zoneIndex));
            List<Routine> batch = routineRepository.findReminderCandidatesInZone(RoutineStatus.ACTIVE, window.time(), window.date(),
                    RoutineLogStatus.COMPLETED, NotificationType.ROUTINE_REMINDER, window.start(), window.end(),
                    cursorId, window.timeZone(), PageRequest.of(0, PAGE_SIZE));
            if (batch.isEmpty()) {
                zoneIndex++;
                cursorId = 0L;
            } else {
                currentBatch = batch.iterator();
            }
        }
        Routine next = currentBatch.next();
        cursorId = next.getId();
        return next;
    }
}
