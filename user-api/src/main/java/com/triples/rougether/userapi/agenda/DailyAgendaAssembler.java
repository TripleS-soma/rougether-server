package com.triples.rougether.userapi.agenda;

import com.triples.rougether.domain.routine.RoutineRecurrence;
import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.userapi.today.dto.TodayCategoryGroup;
import com.triples.rougether.userapi.today.dto.TodayRoutineItem;
import com.triples.rougether.userapi.today.dto.TodaySummary;
import com.triples.rougether.userapi.today.dto.TodayTodoItem;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

// 일별 아젠다(루틴·투두) 조립 로직. today/calendar 조회가 공유함
@Component
public class DailyAgendaAssembler {

    // starts_on~ends_on 범위 안이고 repeat 규칙이 date에 맞으면 대상
    public boolean isRoutineTargetOn(Routine routine, LocalDate date) {
        return RoutineRecurrence.isTargetOn(routine, date);
    }

    // 카테고리별 묶음. 미분류(category=null)는 categoryId=null 그룹으로 분리, 맨 뒤로 둠
    public List<TodayCategoryGroup> groupByCategory(List<Routine> routines,
                                                    Set<Long> completedRoutineIds,
                                                    List<Todo> todos) {
        // categoryId(null=미분류) → 누적기. null 키 허용 위해 LinkedHashMap 사용
        Map<Long, Accumulator> groups = new LinkedHashMap<>();
        for (Routine routine : routines) {
            Long key = categoryIdOf(routine.getCategory());
            groups.computeIfAbsent(key, Accumulator::new)
                    .routines.add(new TodayRoutineItem(routine.getId(), routine.getTitle(),
                            routine.getScheduledTime(), routine.getAuthType(),
                            completedRoutineIds.contains(routine.getId())));
        }
        for (Todo todo : todos) {
            Long key = categoryIdOf(todo.getCategory());
            groups.computeIfAbsent(key, Accumulator::new)
                    .todos.add(new TodayTodoItem(todo.getId(), todo.getTitle(),
                            todo.getDueDate(), todo.getStatus(), todo.getCompletedAt()));
        }

        return groups.values().stream()
                // 분류 그룹은 categoryId 오름차순, 미분류(null)는 맨 뒤
                .sorted(Comparator.comparing(a -> a.categoryId == null ? Long.MAX_VALUE : a.categoryId))
                .map(Accumulator::toGroup)
                .toList();
    }

    public TodaySummary summarize(List<Routine> routines, Set<Long> completedRoutineIds,
                                  List<Todo> todos) {
        int completedRoutines = (int) routines.stream()
                .filter(r -> completedRoutineIds.contains(r.getId())).count();
        int completedTodos = (int) todos.stream()
                .filter(t -> t.getStatus() == TodoStatus.COMPLETED).count();
        int total = routines.size() + todos.size();
        int completedCount = completedRoutines + completedTodos;
        int remainingCount = total - completedCount;
        double progressRate = total == 0 ? 0.0 : (double) completedCount / total;
        return new TodaySummary(completedCount, remainingCount, progressRate);
    }

    // 미분류(category=null)면 null. 연관은 lazy id 접근이라 초기화 없이 FK만 읽음
    private static Long categoryIdOf(Category category) {
        return category != null ? category.getId() : null;
    }

    // 카테고리별 루틴·투두 누적기. 정렬 후 DTO로 변환함
    private static final class Accumulator {
        private final Long categoryId;
        private final List<TodayRoutineItem> routines = new ArrayList<>();
        private final List<TodayTodoItem> todos = new ArrayList<>();

        private Accumulator(Long categoryId) {
            this.categoryId = categoryId;
        }

        private TodayCategoryGroup toGroup() {
            // 루틴은 scheduled_time 시간순(null은 뒤), 그다음 id
            routines.sort(Comparator
                    .comparing(TodayRoutineItem::scheduledTime,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(TodayRoutineItem::id));
            return new TodayCategoryGroup(categoryId, List.copyOf(routines), List.copyOf(todos));
        }
    }
}
