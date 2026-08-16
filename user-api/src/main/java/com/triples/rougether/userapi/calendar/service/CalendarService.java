package com.triples.rougether.userapi.calendar.service;

import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.routine.entity.Todo;
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

    private final RoutineRepository routineRepository;
    private final RoutineLogRepository routineLogRepository;
    private final TodoRepository todoRepository;
    private final DailyAgendaAssembler agendaAssembler;

    @Transactional(readOnly = true)
    public CalendarDayResponse day(Long userId, LocalDate date) {
        LocalDate yesterday = LocalDate.now(KST).minusDays(1);
        if (date.isBefore(yesterday)) {
            return pastDay(userId, date);
        }
        if (date.isEqual(yesterday)) {
            return recalculatedDay(userId, date);
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

    private CalendarDayResponse recalculatedDay(Long userId, LocalDate date) {
        RecalculatedRoutines recalculated = recalculateRoutines(userId, date);
        return assemble(date, recalculated.routines(), recalculated.completedRoutineIds(),
                todosOn(userId, date));
    }

    // 어제(D-1) 소싱: 그날 COMPLETED 로그의 루틴 + 그날 유효했던 버전 중 대상인 루틴을 계보 키로 합집합
    private RecalculatedRoutines recalculateRoutines(Long userId, LocalDate date) {
        List<Routine> routines = new ArrayList<>(routineLogRepository
                .findAllWithRoutineForDay(userId, date)
                .stream()
                .filter(log -> log.getStatus() == RoutineLogStatus.COMPLETED)
                .map(RoutineLog::getRoutine)
                .toList());
        Set<Long> completedRoutineIds = routines.stream()
                .map(Routine::getId)
                .collect(Collectors.toSet());

        Set<Long> seenLineages = routines.stream()
                .map(CalendarService::lineageKey)
                .collect(Collectors.toCollection(HashSet::new));
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

    // 월 캘린더: 그 달 모든 날짜의 대상 루틴·마감 투두 개수. 날짜별 소싱 규칙은 day()와 동일해
    // 달력에서 날짜를 눌러 본 목록의 건수와 일치한다. 날짜마다 조회하지 않고 구간별로 묶어 집계함
    @Transactional(readOnly = true)
    public CalendarMonthResponse month(Long userId, YearMonth yearMonth) {
        LocalDate first = yearMonth.atDay(1);
        LocalDate last = yearMonth.atEndOfMonth();

        Map<LocalDate, Integer> todoCounts = toCountMap(
                todoRepository.countOwnedByDueDateBetween(userId, first, last));
        Map<LocalDate, Integer> routineCounts = routineCountsBetween(userId, first, last);

        List<CalendarDayCount> days = first.datesUntil(last.plusDays(1))
                .map(date -> new CalendarDayCount(date,
                        routineCounts.getOrDefault(date, 0),
                        todoCounts.getOrDefault(date, 0)))
                .toList();
        return new CalendarMonthResponse(yearMonth, days);
    }

    // 기간을 오늘(KST) 기준 세 구간으로 나눠 루틴 개수를 모음. 로그가 없는 과거 날짜는 항목이 없어 0으로 읽힘
    private Map<LocalDate, Integer> routineCountsBetween(Long userId, LocalDate first, LocalDate last) {
        LocalDate today = LocalDate.now(KST);
        LocalDate yesterday = today.minusDays(1);
        Map<LocalDate, Integer> counts = new HashMap<>();

        // 그제 이전: 그날 log(COMPLETED+FAILED) 건수 단독 집계
        LocalDate pastEnd = earlier(last, yesterday.minusDays(1));
        if (!pastEnd.isBefore(first)) {
            counts.putAll(toCountMap(
                    routineLogRepository.countByUserIdAndRoutineDateBetween(userId, first, pastEnd)));
        }
        // 어제: 그날 유효했던 버전으로 재계산
        if (!yesterday.isBefore(first) && !yesterday.isAfter(last)) {
            counts.put(yesterday, recalculateRoutines(userId, yesterday).routines().size());
        }
        // 오늘·미래: 현재 ACTIVE 버전을 한 번만 읽고 날짜마다 반복 대상 여부만 판정
        LocalDate liveStart = later(first, today);
        if (!liveStart.isAfter(last)) {
            counts.putAll(liveRoutineCounts(userId, liveStart, last));
        }
        return counts;
    }

    private Map<LocalDate, Integer> liveRoutineCounts(Long userId, LocalDate from, LocalDate to) {
        List<Routine> activeRoutines = routineRepository
                .findByUserIdAndStatusAndDeletedAtIsNullOrderByScheduledTimeAscOriginRoutineIdAsc(
                        userId, RoutineStatus.ACTIVE);
        return from.datesUntil(to.plusDays(1))
                .collect(Collectors.toMap(
                        date -> date,
                        date -> (int) activeRoutines.stream()
                                .filter(routine -> agendaAssembler.isRoutineTargetOn(routine, date))
                                .count()));
    }

    private static Map<LocalDate, Integer> toCountMap(List<DailyCount> counts) {
        return counts.stream()
                .collect(Collectors.toMap(DailyCount::getTargetDate,
                        count -> (int) count.getItemCount()));
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
