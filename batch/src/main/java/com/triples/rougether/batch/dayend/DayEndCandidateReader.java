package com.triples.rougether.batch.dayend;

import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.data.domain.PageRequest;

@RequiredArgsConstructor
class DayEndCandidateReader implements ItemReader<Routine> {

    private static final int PAGE_SIZE = 200;

    private final RoutineRepository routineRepository;
    private final Instant dayEndExclusive;
    private final LocalDate targetDate;

    private Iterator<Routine> currentBatch = Collections.emptyIterator();
    private long cursorId = 0L;
    private boolean exhausted = false;

    @Override
    public Routine read() {
        if (!currentBatch.hasNext() && !exhausted) {
            List<Routine> batch = routineRepository.findDayEndFailCandidates(
                    cursorId, dayEndExclusive, targetDate, PageRequest.of(0, PAGE_SIZE));
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
}
