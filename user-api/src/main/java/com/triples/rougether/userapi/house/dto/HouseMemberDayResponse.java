package com.triples.rougether.userapi.house.dto;

import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

// 집 멤버의 그날 현황(루틴 + 투두, 완료 여부 포함). 오늘 현황(TodayRoutineItem/TodayTodoItem) 스타일에 맞춤.
public record HouseMemberDayResponse(
        @Schema(description = "기준 날짜(YYYY-MM-DD). date 미지정 시 오늘(KST)", example = "2026-07-08")
        LocalDate date,
        @Schema(description = "그날 반복 대상 루틴 목록. 수행 예정 시각 오름차순")
        List<MemberRoutineItem> routines,
        @Schema(description = "그날 마감(dueDate=date) 투두 목록. id 오름차순")
        List<MemberTodoItem> todos) {

    public record MemberRoutineItem(
            @Schema(description = "루틴 ID (버전 id — 스케줄 수정 시 바뀔 수 있음)", example = "12")
            Long id,
            @Schema(description = "루틴 계보 루트 ID. 버전이 바뀌어도 불변 — 프론트 목록 key 로 사용", example = "10")
            Long originRoutineId,
            @Schema(description = "루틴 제목", example = "아침 운동")
            String title,
            @Schema(description = "수행 예정 시각(HH:mm:ss, 미지정이면 null)", example = "07:00:00")
            LocalTime scheduledTime,
            @Schema(description = "인증 방식. 허용값: CHECK(체크형), PHOTO(사진 인증형)", example = "CHECK")
            AuthType authType,
            @Schema(description = "루틴의 카테고리 ID (조회 대상 회원 소유의 카테고리)", example = "3")
            Long categoryId,
            @Schema(description = "기준일 완료 여부", example = "true")
            boolean completed) {

        public static MemberRoutineItem of(Routine routine, boolean completed) {
            return new MemberRoutineItem(
                    routine.getId(),
                    routine.getOriginRoutineId(),
                    routine.getTitle(),
                    routine.getScheduledTime(),
                    routine.getAuthType(),
                    routine.getCategory() != null ? routine.getCategory().getId() : null,
                    completed);
        }
    }

    public record MemberTodoItem(
            @Schema(description = "투두 ID", example = "5")
            Long id,
            @Schema(description = "투두 제목", example = "장보기")
            String title,
            @Schema(description = "투두 상태. 허용값: PENDING(대기), COMPLETED(완료)", example = "COMPLETED")
            TodoStatus status,
            @Schema(description = "완료 시각(미완료면 null)", example = "2026-07-08T07:00:00Z")
            Instant completedAt,
            @Schema(description = "투두의 카테고리 ID (조회 대상 회원 소유의 카테고리)", example = "3")
            Long categoryId) {

        public static MemberTodoItem of(Todo todo) {
            return new MemberTodoItem(
                    todo.getId(),
                    todo.getTitle(),
                    todo.getStatus(),
                    todo.getCompletedAt(),
                    todo.getCategory() != null ? todo.getCategory().getId() : null);
        }
    }
}
