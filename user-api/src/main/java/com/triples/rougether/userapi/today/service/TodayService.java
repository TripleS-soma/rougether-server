package com.triples.rougether.userapi.today.service;

import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.routine.repository.RoutineDayLogRow;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.routine.repository.TodoAgendaRow;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.userapi.agenda.DailyAgendaAssembler;
import com.triples.rougether.userapi.agenda.LiveRoutineSelector;
import com.triples.rougether.userapi.agenda.dto.AgendaCategoryGroup;
import com.triples.rougether.userapi.agenda.dto.AgendaSummary;
import com.triples.rougether.userapi.today.dto.TodayResponse;
import com.triples.rougether.userapi.today.dto.TodayStreak;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TodayService {

    // KST 고정 — "오늘"·요일 판정 모두 이 기준임
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RoutineRepository routineRepository;
    private final RoutineLogRepository routineLogRepository;
    private final TodoRepository todoRepository;
    private final StreakRepository streakRepository;
    private final DailyAgendaAssembler agendaAssembler;
    private final LiveRoutineSelector liveRoutineSelector;

    @Transactional(readOnly = true)
    public TodayResponse today(Long userId) {
        return today(userId, LocalDate.now(KST));
    }

    // 기준일을 받는 조회 — 요일·기간 판정을 결정적으로 검증하는 테스트에서만 직접 호출함
    @Transactional(readOnly = true)
    TodayResponse today(Long userId, LocalDate targetDate) {
        List<Routine> candidates = routineRepository
                .findAgendaCandidates(userId, RoutineStatus.ACTIVE, targetDate);
        List<Routine> targets = liveRoutineSelector.targetsOn(candidates, targetDate);

        // 대상 루틴이 없으면 완료·건너뜀 로그 조회를 생략함(쿼리 예산 3회)
        List<RoutineDayLogRow> dayLogs = targets.isEmpty() ? List.of() : routineLogRepository
                .findDayLogRowsOn(userId, targetDate, LiveRoutineSelector.LOG_STATUSES);
        LiveRoutineSelector.Selection selection = liveRoutineSelector.applyDayLogs(targets, dayLogs);
        List<Routine> routines = selection.routines();
        Set<Long> completedRoutineIds = selection.completedRoutineIds();

        // 마감일이 정확히 오늘인 투두만(overdue·밀린 투두 제외 — calendar와 동일)
        List<TodoAgendaRow> todos = todoRepository.findAgendaDueOn(userId, targetDate);

        List<AgendaCategoryGroup> categories = agendaAssembler.groupByCategory(routines, completedRoutineIds, todos);
        AgendaSummary summary = agendaAssembler.summarize(routines, completedRoutineIds, todos);
        TodayStreak streak = TodayStreak.from(
                streakRepository.findByUserId(userId).orElse(null), targetDate);
        return new TodayResponse(targetDate, categories, summary, streak);
    }
}
