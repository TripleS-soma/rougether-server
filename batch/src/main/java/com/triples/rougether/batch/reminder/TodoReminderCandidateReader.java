package com.triples.rougether.batch.reminder;

import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.data.domain.PageRequest;

// ReminderCandidateReader와 동일한 이유로 offset 대신 id 커서(id > cursorId)로 페이징한다
@RequiredArgsConstructor
class TodoReminderCandidateReader implements ItemReader<Todo> {

    private static final int PAGE_SIZE = 200;

    private final TodoRepository todoRepository;
    private final TodoStatus status;
    private final LocalDate date;
    private final LocalTime dueTime;
    private final NotificationType notificationType;
    private final Instant dayStart;
    private final Instant dayEndExclusive;

    private Iterator<Todo> currentBatch = Collections.emptyIterator();
    private Long cursorId = 0L;
    private boolean exhausted = false;

    @Override
    public Todo read() {
        if (!currentBatch.hasNext() && !exhausted) {
            List<Todo> batch = fetchNextBatch();
            if (batch.isEmpty()) {
                exhausted = true;
            } else {
                currentBatch = batch.iterator();
            }
        }
        if (!currentBatch.hasNext()) {
            return null;
        }
        Todo next = currentBatch.next();
        cursorId = next.getId();
        return next;
    }

    private List<Todo> fetchNextBatch() {
        return todoRepository.findReminderCandidates(status, date, dueTime, notificationType,
                dayStart, dayEndExclusive, cursorId, PageRequest.of(0, PAGE_SIZE));
    }
}
