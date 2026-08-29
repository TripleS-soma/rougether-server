package com.triples.rougether.batch.eveningdigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.member.entity.User;
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
import org.junit.jupiter.api.Test;

class EveningDigestProcessorTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 31);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RoutineRepository routineRepository = mock(RoutineRepository.class);
    private final TodoRepository todoRepository = mock(TodoRepository.class);
    private final EveningDigestProcessor processor =
            new EveningDigestProcessor(routineRepository, todoRepository, MONDAY);

    @Test
    void 오늘_반복_대상_중_완료하지_않은_루틴과_오늘_PENDING_투두만_스냅숏한다() throws Exception {
        User user = user(10L);
        Routine incompleteDaily = routine(101L, 1001L, "DAILY", null);
        Routine offDayWeekly = routine(103L, 1003L, "WEEKLY", "{\"daysOfWeek\":[\"TUE\"]}");
        Todo pending = todo(201L);
        when(routineRepository.findDailyIncompleteDigestRoutineCandidates(
                10L, MONDAY, MONDAY.plusDays(1).atStartOfDay(KST).toInstant(),
                RoutineStatus.ACTIVE, RoutineLogStatus.COMPLETED))
                .thenReturn(List.of(incompleteDaily, offDayWeekly));
        when(todoRepository.findDailyIncompleteDigestTodoCandidates(10L, MONDAY, TodoStatus.PENDING))
                .thenReturn(List.of(pending));

        EveningDigestDraft result = processor.process(user);

        assertThat(result.routineLineageIds()).containsExactly(1001L);
        assertThat(result.todoIds()).containsExactly(201L);
        assertThat(result.routineCount()).isEqualTo(1);
        assertThat(result.todoCount()).isEqualTo(1);
    }

    @Test
    void 미완료가_하나도_없으면_알림_초안을_만들지_않는다() throws Exception {
        User user = user(20L);
        when(routineRepository.findDailyIncompleteDigestRoutineCandidates(
                20L, MONDAY, MONDAY.plusDays(1).atStartOfDay(KST).toInstant(),
                RoutineStatus.ACTIVE, RoutineLogStatus.COMPLETED)).thenReturn(List.of());
        when(todoRepository.findDailyIncompleteDigestTodoCandidates(20L, MONDAY, TodoStatus.PENDING))
                .thenReturn(List.of());

        assertThat(processor.process(user)).isNull();
    }

    private static User user(long id) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        return user;
    }

    private static Routine routine(long id, long lineageId, String repeatType, String repeatDays) {
        Routine routine = mock(Routine.class);
        when(routine.getId()).thenReturn(id);
        when(routine.getOriginRoutineId()).thenReturn(lineageId);
        when(routine.getRepeatType()).thenReturn(repeatType);
        when(routine.getRepeatDays()).thenReturn(repeatDays);
        return routine;
    }

    private static Todo todo(long id) {
        Todo todo = mock(Todo.class);
        when(todo.getId()).thenReturn(id);
        return todo;
    }
}
