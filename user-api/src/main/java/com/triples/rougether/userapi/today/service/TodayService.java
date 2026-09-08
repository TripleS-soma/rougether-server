package com.triples.rougether.userapi.today.service;

import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.userapi.agenda.DailyAgendaAssembler;
import com.triples.rougether.userapi.today.dto.TodayCategoryGroup;
import com.triples.rougether.userapi.today.dto.TodayResponse;
import com.triples.rougether.userapi.today.dto.TodayStreak;
import com.triples.rougether.userapi.today.dto.TodaySummary;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TodayService {

    private final RoutineRepository routineRepository;
    private final RoutineLogRepository routineLogRepository;
    private final TodoRepository todoRepository;
    private final StreakRepository streakRepository;
    private final DailyAgendaAssembler agendaAssembler;
    // KST Clock(kstClock 빈). "오늘" 기준일이 이 시계에서 나옴(테스트 고정용)
    private final Clock clock;

    @Transactional(readOnly = true)
    public TodayResponse today(Long userId) {
        return today(userId, LocalDate.now(clock));
    }

    // 기준일을 받는 조회 — 요일·기간 판정을 결정적으로 검증하는 테스트에서만 직접 호출함
    @Transactional(readOnly = true)
    TodayResponse today(Long userId, LocalDate targetDate) {
        // ACTIVE 루틴 중 오늘 반복 대상만 추림(repeat·기간 판정은 in-app)
        List<Routine> routines = routineRepository
                .findByUserIdAndStatusAndDeletedAtIsNullOrderByScheduledTimeAscOriginRoutineIdAsc(
                        userId, RoutineStatus.ACTIVE)
                .stream()
                .filter(routine -> agendaAssembler.isRoutineTargetOn(routine, targetDate))
                .toList();

        // 당일 완료한 루틴 id 집합
        Set<Long> completedRoutineIds = routineLogRepository
                .findByRoutine_UserIdAndRoutineDateAndStatus(userId, targetDate, RoutineLogStatus.COMPLETED)
                .stream()
                .map(log -> log.getRoutine().getId())
                .collect(Collectors.toSet());

        // 마감일이 정확히 오늘인 투두만(overdue·밀린 투두 제외 — calendar와 동일)
        List<Todo> todos = todoRepository.findOwnedWithFilters(userId, null, null, targetDate);

        List<TodayCategoryGroup> categories = agendaAssembler.groupByCategory(routines, completedRoutineIds, todos);
        TodaySummary summary = agendaAssembler.summarize(routines, completedRoutineIds, todos);
        TodayStreak streak = TodayStreak.from(
                streakRepository.findByUserId(userId).orElse(null), targetDate);
        return new TodayResponse(targetDate, categories, summary, streak);
    }
}
