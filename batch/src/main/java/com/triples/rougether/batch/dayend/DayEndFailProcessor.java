package com.triples.rougether.batch.dayend;

import com.triples.rougether.domain.routine.RoutineRecurrence;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.ItemProcessor;

@RequiredArgsConstructor
class DayEndFailProcessor implements ItemProcessor<Routine, RoutineLog> {

    private final LocalDate targetDate;

    @Override
    public RoutineLog process(Routine routine) {
        if (!RoutineRecurrence.isTargetOn(routine, targetDate)) {
            return null;
        }
        return RoutineLog.fail(routine, targetDate);
    }
}
