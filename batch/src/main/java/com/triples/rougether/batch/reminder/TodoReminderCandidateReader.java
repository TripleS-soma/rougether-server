package com.triples.rougether.batch.reminder;

import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import java.time.Instant;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.data.domain.PageRequest;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;

@RequiredArgsConstructor
class TodoReminderCandidateReader implements ItemReader<Todo> {
    private static final int PAGE_SIZE = 200;
    private final TodoRepository todoRepository;
    private final Instant targetInstant;
    private final List<String> timeZones;
    private Iterator<Todo> currentBatch = Collections.emptyIterator();
    private int zoneIndex;
    private Long cursorId = 0L;

    @Override
    public Todo read() {
        while (!currentBatch.hasNext()) {
            if (zoneIndex >= timeZones.size()) { return null; }
            var window = ReminderWindow.at(targetInstant, timeZones.get(zoneIndex));
            List<Todo> batch = todoRepository.findReminderCandidatesInZone(TodoStatus.PENDING, window.date(), window.time(),
                    NotificationType.TODO_REMINDER, window.start(), window.end(), cursorId, window.timeZone(),
                    PageRequest.of(0, PAGE_SIZE));
            if (batch.isEmpty()) {
                zoneIndex++;
                cursorId = 0L;
            } else {
                currentBatch = batch.iterator();
            }
        }
        Todo next = currentBatch.next();
        cursorId = next.getId();
        return next;
    }
}
