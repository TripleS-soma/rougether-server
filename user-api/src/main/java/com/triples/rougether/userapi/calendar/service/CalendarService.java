package com.triples.rougether.userapi.calendar.service;

import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
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
                .findByUserIdAndStatusAndDeletedAtIsNullOrderByScheduledTimeAscOriginRoutineIdAsc(
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

    // 과거: 그날 log(COMPLETED+FAILED) 단독 조회. 판정은 day-end 배치가 끝냈으므로 재구성하지 않고,
    // 표시값은 log가 가리키는 버전 row에서 읽음(루틴은 soft delete라 버전 row가 남아 있음)
    private CalendarDayResponse pastDay(Long userId, LocalDate date) {
        List<RoutineLog> logs = routineLogRepository.findAllWithRoutineForDay(userId, date);

        List<Routine> routines = logs.stream()
                .map(RoutineLog::getRoutine)
                .toList();
        Set<Long> completedRoutineIds = logs.stream()
                .filter(log -> log.getStatus() == RoutineLogStatus.COMPLETED)
                .map(log -> log.getRoutine().getId())
                .collect(Collectors.toSet());

        return assemble(date, routines, completedRoutineIds, todosOn(userId, date));
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
