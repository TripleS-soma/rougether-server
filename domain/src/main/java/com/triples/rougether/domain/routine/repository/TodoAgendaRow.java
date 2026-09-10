package com.triples.rougether.domain.routine.repository;

import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

// 일별 아젠다 응답에 필요한 값만 조회함. 설명·외부 연동·보상 엔티티 로딩을 피함.
public record TodoAgendaRow(Long id, Long categoryId, String title, LocalDate dueDate,
                            LocalTime dueTime, TodoStatus status, Instant completedAt) {

    // 과거 달력의 엔티티 조회 결과도 동일한 조립 경로를 사용함.
    public static TodoAgendaRow from(Todo todo) {
        return new TodoAgendaRow(todo.getId(),
                todo.getCategory() == null ? null : todo.getCategory().getId(),
                todo.getTitle(), todo.getDueDate(), todo.getDueTime(),
                todo.getStatus(), todo.getCompletedAt());
    }
}
