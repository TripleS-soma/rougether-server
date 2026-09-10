package com.triples.rougether.userapi.today.service;

import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.routine.repository.TodoAgendaRow;
import com.triples.rougether.userapi.agenda.DailyAgendaAssembler;
import com.triples.rougether.userapi.today.dto.TodayCategoryGroup;
import com.triples.rougether.userapi.today.dto.TodayResponse;
import com.triples.rougether.userapi.today.dto.TodayStreak;
import com.triples.rougether.userapi.today.dto.TodaySummary;
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

    @Transactional(readOnly = true)
    public TodayResponse today(Long userId) {
        return today(userId, LocalDate.now(KST));
    }

    // 기준일을 받는 조회 — 요일·기간 판정을 결정적으로 검증하는 테스트에서만 직접 호출함
    @Transactional(readOnly = true)
    TodayResponse today(Long userId, LocalDate targetDate) {
        // 기간 내 ACTIVE 루틴 중 오늘 반복 대상만 추림.
        List<Routine> routines = routineRepository
                .findAgendaCandidates(userId, RoutineStatus.ACTIVE, targetDate)
                .stream()
                .filter(routine -> agendaAssembler.isRoutineTargetOn(routine, targetDate))
                .toList();

        // 대상 루틴이 없으면 완료 로그 조회 결과를 사용하지 않으므로 생략함.
        Set<Long> completedRoutineIds = routines.isEmpty() ? Set.of() : routineLogRepository
                .findRoutineIdsCompletedOn(userId, targetDate, RoutineLogStatus.COMPLETED);

        // 마감일이 정확히 오늘인 투두만(overdue·밀린 투두 제외 — calendar와 동일)
        List<TodoAgendaRow> todos = todoRepository.findAgendaDueOn(userId, targetDate);

        List<TodayCategoryGroup> categories = agendaAssembler.groupByCategory(routines, completedRoutineIds, todos);
        TodaySummary summary = agendaAssembler.summarize(routines, completedRoutineIds, todos);
        TodayStreak streak = TodayStreak.from(
                streakRepository.findByUserId(userId).orElse(null), targetDate);
        return new TodayResponse(targetDate, categories, summary, streak);
    }
}
