package com.triples.rougether.batch.eveningdigest;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.routine.RoutineRecurrence;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.batch.infrastructure.item.ItemProcessor;

class EveningDigestProcessor implements ItemProcessor<User, EveningDigestDraft> {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RoutineRepository routineRepository;
    private final TodoRepository todoRepository;
    private final LocalDate targetDate;

    EveningDigestProcessor(RoutineRepository routineRepository, TodoRepository todoRepository,
            LocalDate targetDate) {
        this.routineRepository = routineRepository;
        this.todoRepository = todoRepository;
        this.targetDate = targetDate;
    }

    @Override
    public EveningDigestDraft process(User user) {
        Long userId = user.getId();
        List<Long> incompleteRoutineLineages = routineRepository
                .findDailyIncompleteDigestRoutineCandidates(
                        userId,
                        targetDate,
                        targetDate.plusDays(1).atStartOfDay(KST).toInstant(),
                        RoutineStatus.ACTIVE,
                        RoutineLogStatus.COMPLETED)
                .stream()
                .filter(routine -> RoutineRecurrence.isTargetOn(routine, targetDate))
                .map(EveningDigestProcessor::lineageId)
                .distinct()
                .collect(Collectors.toList());
        List<Long> incompleteTodoIds = todoRepository
                .findDailyIncompleteDigestTodoCandidates(userId, targetDate, TodoStatus.PENDING)
                .stream()
                .map(Todo::getId)
                .toList();

        if (incompleteRoutineLineages.isEmpty() && incompleteTodoIds.isEmpty()) {
            return null;
        }
        return new EveningDigestDraft(user, targetDate, incompleteRoutineLineages, incompleteTodoIds);
    }

    private static Long lineageId(Routine routine) {
        return routine.getOriginRoutineId() != null ? routine.getOriginRoutineId() : routine.getId();
    }
}
