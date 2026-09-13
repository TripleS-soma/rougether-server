package com.triples.rougether.userapi.calendar.service;

import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.RoutineDayLogRow;
import com.triples.rougether.domain.routine.repository.TodoAgendaRow;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.support.DailyCount;
import com.triples.rougether.userapi.agenda.DailyAgendaAssembler;
import com.triples.rougether.userapi.calendar.dto.CalendarDayCount;
import com.triples.rougether.userapi.calendar.dto.CalendarDayResponse;
import com.triples.rougether.userapi.calendar.dto.CalendarMonthResponse;
import com.triples.rougether.userapi.today.dto.TodayCategoryGroup;
import com.triples.rougether.userapi.today.dto.TodaySummary;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CalendarService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 그날 한 번에 읽는 로그 상태: 완료 대조 + 건너뜀 제외(mobile #189)
    private static final List<RoutineLogStatus> DAY_LOG_STATUSES =
            List.of(RoutineLogStatus.COMPLETED, RoutineLogStatus.SKIPPED);

    private final RoutineRepository routineRepository;
    private final RoutineLogRepository routineLogRepository;
    private final TodoRepository todoRepository;
    private final DailyAgendaAssembler agendaAssembler;
    private final Clock clock;

    @Transactional(readOnly = true)
    public CalendarDayResponse day(Long userId, LocalDate date) {
        LocalDate yesterday = LocalDate.now(clock.withZone(KST)).minusDays(1);
        if (date.isBefore(yesterday)) {
            return pastDay(userId, date);
        }
        if (date.isEqual(yesterday)) {
            return recalculatedDay(userId, date);
        }
        return liveDay(userId, date);
    }

    private CalendarDayResponse liveDay(Long userId, LocalDate date) {
        // 그날 완료·건너뜀 로그를 한 번에 읽음(엔티티 로드 없이 id·계보 키만)
        List<RoutineDayLogRow> dayLogs = routineLogRepository.findDayLogRowsOn(userId, date, DAY_LOG_STATUSES);
        Set<Long> skippedLineages = lineageKeysOf(dayLogs, RoutineLogStatus.SKIPPED);
        // ACTIVE 루틴 중 그날 반복 대상만 추림
        List<Routine> routines = routineRepository
                .findByUserIdAndStatusAndDeletedAtIsNullOrderByScheduledTimeAscOriginRoutineIdAsc(
                        userId, RoutineStatus.ACTIVE)
                .stream()
                .filter(routine -> agendaAssembler.isRoutineTargetOn(routine, date))
                // 건너뛴 발생분(SKIPPED, mobile #189)은 계보 단위로 뺀다
                .filter(routine -> !skippedLineages.contains(lineageKey(routine)))
                .toList();

        // 그날 완료한 루틴 id 집합
        Set<Long> completedRoutineIds = dayLogs.stream()
                .filter(row -> row.getStatus() == RoutineLogStatus.COMPLETED)
                .map(RoutineDayLogRow::getRoutineId)
                .collect(Collectors.toSet());

        return assemble(date, routines, completedRoutineIds, todosOn(userId, date));
    }

    // 과거: 그날 log(COMPLETED+FAILED) 단독 조회. 판정은 day-end 배치가 끝냈으므로 재구성하지 않고,
    // 표시값은 log가 가리키는 버전 row에서 읽음(루틴은 soft delete라 버전 row가 남아 있음)
    private CalendarDayResponse pastDay(Long userId, LocalDate date) {
        // SKIPPED(건너뜀, mobile #189)는 그날 수행 대상이 아니었던 것으로 보아 노출하지 않는다
        List<RoutineLog> logs = routineLogRepository.findAllWithRoutineForDay(userId, date).stream()
                .filter(log -> log.getStatus() != RoutineLogStatus.SKIPPED)
                .toList();

        List<Routine> routines = logs.stream()
                .map(RoutineLog::getRoutine)
                .toList();
        Set<Long> completedRoutineIds = logs.stream()
                .filter(log -> log.getStatus() == RoutineLogStatus.COMPLETED)
                .map(log -> log.getRoutine().getId())
                .collect(Collectors.toSet());

        return assemble(date, routines, completedRoutineIds, todosOn(userId, date));
    }

    private CalendarDayResponse recalculatedDay(Long userId, LocalDate date) {
        RecalculatedRoutines recalculated = recalculateRoutines(userId, date);
        return assemble(date, recalculated.routines(), recalculated.completedRoutineIds(),
                todosOn(userId, date));
    }

    // 어제(D-1) 소싱: 그날 COMPLETED 로그의 루틴 + 그날 유효했던 버전 중 대상인 루틴을 계보 키로 합집합
    private RecalculatedRoutines recalculateRoutines(Long userId, LocalDate date) {
        List<RoutineLog> dayLogs = routineLogRepository.findAllWithRoutineForDay(userId, date);
        List<Routine> routines = new ArrayList<>(dayLogs.stream()
                .filter(log -> log.getStatus() == RoutineLogStatus.COMPLETED)
                .map(RoutineLog::getRoutine)
                .toList());
        Set<Long> completedRoutineIds = routines.stream()
                .map(Routine::getId)
                .collect(Collectors.toSet());

        Set<Long> seenLineages = routines.stream()
                .map(CalendarService::lineageKey)
                .collect(Collectors.toCollection(HashSet::new));
        // 건너뛴 계보(SKIPPED, mobile #189)는 재계산으로도 다시 채우지 않는다 — 본 것으로만 표시하고 루틴은 추가 안 함
        dayLogs.stream()
                .filter(log -> log.getStatus() == RoutineLogStatus.SKIPPED)
                .map(log -> lineageKey(log.getRoutine()))
                .forEach(seenLineages::add);
        for (Routine routine : routineRepository.findEffectiveOnDay(userId, date)) {
            if (agendaAssembler.isRoutineTargetOn(routine, date)
                    && seenLineages.add(lineageKey(routine))) {
                routines.add(routine);
            }
        }
        return new RecalculatedRoutines(List.copyOf(routines), Set.copyOf(completedRoutineIds));
    }

    private record RecalculatedRoutines(List<Routine> routines, Set<Long> completedRoutineIds) {
    }

    // 월 캘린더: 전체·완료 개수를 day()와 같은 표시 대상에서 집계함.
    // 날짜마다 조회하지 않고 구간별로 묶으며 요청 시작 시점의 KST 날짜로 구간을 고정함
    @Transactional(readOnly = true)
    public CalendarMonthResponse month(Long userId, YearMonth yearMonth) {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        LocalDate first = yearMonth.atDay(1);
        LocalDate last = yearMonth.atEndOfMonth();

        Map<LocalDate, Counts> todoCounts = toCountMap(
                todoRepository.countOwnedByDueDateBetween(userId, first, last, TodoStatus.COMPLETED));
        Map<LocalDate, Counts> routineCounts = routineCountsBetween(userId, first, last, today);

        List<CalendarDayCount> days = first.datesUntil(last.plusDays(1))
                .map(date -> {
                    Counts routines = routineCounts.getOrDefault(date, Counts.EMPTY);
                    Counts todos = todoCounts.getOrDefault(date, Counts.EMPTY);
                    return new CalendarDayCount(date, routines.total(), todos.total(),
                            routines.completed(), todos.completed());
                })
                .toList();
        return new CalendarMonthResponse(yearMonth, days);
    }

    // 기간을 오늘(KST) 기준 세 구간으로 나눠 루틴 개수를 모음. 로그가 없는 과거 날짜는 항목이 없어 0으로 읽힘
    private Map<LocalDate, Counts> routineCountsBetween(
            Long userId, LocalDate first, LocalDate last, LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        Map<LocalDate, Counts> counts = new HashMap<>();

        // 그제 이전: 그날 log(COMPLETED+FAILED) 건수 단독 집계
        LocalDate pastEnd = earlier(last, yesterday.minusDays(1));
        if (!pastEnd.isBefore(first)) {
            counts.putAll(toCountMap(
                    routineLogRepository.countByUserIdAndRoutineDateBetween(
                            userId, first, pastEnd, RoutineLogStatus.COMPLETED)));
        }
        // 어제: 그날 유효했던 버전으로 재계산
        if (!yesterday.isBefore(first) && !yesterday.isAfter(last)) {
            RecalculatedRoutines recalculated = recalculateRoutines(userId, yesterday);
            counts.put(yesterday, countRoutines(
                    recalculated.routines(), recalculated.completedRoutineIds()));
        }
        // 오늘·미래: 현재 ACTIVE 버전을 한 번만 읽고 날짜마다 반복 대상 여부만 판정
        LocalDate liveStart = later(first, today);
        if (!liveStart.isAfter(last)) {
            counts.putAll(liveRoutineCounts(userId, liveStart, last));
        }
        return counts;
    }

    private Map<LocalDate, Counts> liveRoutineCounts(Long userId, LocalDate from, LocalDate to) {
        List<Routine> activeRoutines = routineRepository
                .findByUserIdAndStatusAndDeletedAtIsNullOrderByScheduledTimeAscOriginRoutineIdAsc(
                        userId, RoutineStatus.ACTIVE);
        // 완료·건너뜀을 한 쿼리로 읽어 날짜별로 가른다(월 조회 쿼리 예산 6회 유지)
        List<RoutineDayLogRow> logRows = routineLogRepository.findDayLogRowsBetween(userId, from, to, DAY_LOG_STATUSES);
        Map<LocalDate, Set<Long>> completedIdsByDate = logRows.stream()
                .filter(row -> row.getStatus() == RoutineLogStatus.COMPLETED)
                .collect(Collectors.groupingBy(RoutineDayLogRow::getRoutineDate,
                        Collectors.mapping(RoutineDayLogRow::getRoutineId, Collectors.toSet())));
        // 건너뛴 발생분(SKIPPED, mobile #189)은 날짜별 분모에서도 뺀다 — /calendar 의 live 소싱과 같은 기준
        Map<LocalDate, Set<Long>> skippedLineagesByDate = logRows.stream()
                .filter(row -> row.getStatus() == RoutineLogStatus.SKIPPED)
                .collect(Collectors.groupingBy(RoutineDayLogRow::getRoutineDate,
                        Collectors.mapping(RoutineDayLogRow::getLineageKey, Collectors.toSet())));
        return from.datesUntil(to.plusDays(1))
                .collect(Collectors.toMap(
                        date -> date,
                        date -> countRoutines(activeRoutines.stream()
                                        .filter(routine -> agendaAssembler.isRoutineTargetOn(routine, date))
                                        .filter(routine -> !skippedLineagesByDate
                                                .getOrDefault(date, Set.of()).contains(lineageKey(routine)))
                                        .toList(),
                                completedIdsByDate.getOrDefault(date, Set.of()))));
    }

    private static Counts countRoutines(List<Routine> routines, Set<Long> completedRoutineIds) {
        int completed = (int) routines.stream()
                .filter(routine -> completedRoutineIds.contains(routine.getId()))
                .count();
        return new Counts(routines.size(), completed);
    }

    private record Counts(int total, int completed) {
        private static final Counts EMPTY = new Counts(0, 0);
    }

    private static Map<LocalDate, Counts> toCountMap(List<DailyCount> counts) {
        return counts.stream()
                .collect(Collectors.toMap(DailyCount::getTargetDate,
                        count -> new Counts(Math.toIntExact(count.getItemCount()),
                                Math.toIntExact(count.getCompletedCount()))));
    }

    private static LocalDate earlier(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private static LocalDate later(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static Long lineageKey(Routine routine) {
        return routine.getOriginRoutineId() != null ? routine.getOriginRoutineId() : routine.getId();
    }

    private static Set<Long> lineageKeysOf(List<RoutineDayLogRow> rows, RoutineLogStatus status) {
        return rows.stream()
                .filter(row -> row.getStatus() == status)
                .map(RoutineDayLogRow::getLineageKey)
                .collect(Collectors.toSet());
    }

    // 마감일이 정확히 그날인 투두만
    private List<Todo> todosOn(Long userId, LocalDate date) {
        return todoRepository.findOwnedWithFilters(userId, null, null, date);
    }

    private CalendarDayResponse assemble(LocalDate date, List<Routine> routines,
                                         Set<Long> completedRoutineIds, List<Todo> todos) {
        List<TodoAgendaRow> todoRows = todos.stream().map(TodoAgendaRow::from).toList();
        List<TodayCategoryGroup> categories =
                agendaAssembler.groupByCategory(routines, completedRoutineIds, todoRows);
        TodaySummary summary = agendaAssembler.summarize(routines, completedRoutineIds, todoRows);
        return new CalendarDayResponse(date, categories, summary);
    }
}
