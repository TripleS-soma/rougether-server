package com.triples.rougether.userapi.calendar.service;

import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.userapi.agenda.DailyAgendaAssembler;
import com.triples.rougether.userapi.calendar.dto.CalendarDayResponse;
import com.triples.rougether.userapi.today.dto.TodayCategoryGroup;
import com.triples.rougether.userapi.today.dto.TodaySummary;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CalendarService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RoutineRepository routineRepository;
    private final RoutineLogRepository routineLogRepository;
    private final TodoRepository todoRepository;
    private final DailyAgendaAssembler agendaAssembler;

    @Transactional(readOnly = true)
    public CalendarDayResponse day(Long userId, LocalDate date) {
        // 과거는 실제 기록(완료 log)에서, 오늘·미래는 live 재계산에서 소싱함
        if (date.isBefore(LocalDate.now(KST))) {
            return pastDay(userId, date);
        }
        return liveDay(userId, date);
    }

    private CalendarDayResponse liveDay(Long userId, LocalDate date) {
        // ACTIVE 루틴 중 그날 반복 대상만 추림
        List<Routine> routines = routineRepository
                .findByUserIdAndStatusAndDeletedAtIsNullOrderByScheduledTimeAscIdAsc(
                        userId, RoutineStatus.ACTIVE)
                .stream()
                .filter(routine -> agendaAssembler.isRoutineTargetOn(routine, date))
                .toList();

        // 그날 완료한 루틴 id 집합
        Set<Long> completedRoutineIds = routineLogRepository
                .findByRoutine_UserIdAndRoutineDateAndStatus(userId, date, RoutineLogStatus.COMPLETED)
                .stream()
                .map(log -> log.getRoutine().getId())
                .collect(Collectors.toSet());

        return assemble(date, routines, completedRoutineIds, todosOn(userId, date));
    }

    // 과거: 그날 COMPLETED 루틴 log에서만 루틴을 소싱함(미완료 과거 루틴은 표시 안 함).
    // log가 가리키는 루틴은 soft-deleted여도 현재값(title·categoryId·scheduledTime)으로 읽음
    private CalendarDayResponse pastDay(Long userId, LocalDate date) {
        // 완료 log → 루틴(중복 완료는 guard로 막혀 있으나 방어적으로 distinct)
        List<Routine> completedRoutines = routineLogRepository
                .findCompletedWithRoutineForDay(userId, date, RoutineLogStatus.COMPLETED)
                .stream()
                .map(log -> log.getRoutine())
                .distinct()
                .toList();

        // 과거에 노출되는 루틴은 전부 완료된 것뿐 — completed 집합은 곧 노출 루틴 전체
        Set<Long> completedRoutineIds = completedRoutines.stream()
                .map(Routine::getId)
                .collect(Collectors.toSet());

        return assemble(date, completedRoutines, completedRoutineIds, todosOn(userId, date));
    }

    // 마감일이 정확히 그날인 투두만
    private List<Todo> todosOn(Long userId, LocalDate date) {
        return todoRepository.findOwnedWithFilters(userId, null, null, date);
    }

    private CalendarDayResponse assemble(LocalDate date, List<Routine> routines,
                                         Set<Long> completedRoutineIds, List<Todo> todos) {
        List<TodayCategoryGroup> categories =
                agendaAssembler.groupByCategory(routines, completedRoutineIds, todos);
        TodaySummary summary = agendaAssembler.summarize(routines, completedRoutineIds, todos);
        return new CalendarDayResponse(date, categories, summary);
    }
}
