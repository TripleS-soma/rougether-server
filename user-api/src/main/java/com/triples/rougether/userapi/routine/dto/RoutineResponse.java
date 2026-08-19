package com.triples.rougether.userapi.routine.dto;

import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalTime;

public record RoutineResponse(
        @Schema(description = "루틴 ID", example = "1")
        Long id,
        @Schema(description = "루틴 제목", example = "아침 운동")
        String title,
        @Schema(description = "소속 카테고리 ID(미분류면 null). 내 카테고리 목록 조회(GET /api/v1/categories) 응답의 id에 대응", example = "3")
        Long categoryId,
        @Schema(description = "인증 방식. 허용값: CHECK(체크형), PHOTO(사진 인증형)", example = "CHECK")
        AuthType authType,
        @Schema(description = "루틴 상태. 허용값: ACTIVE(진행 중)", example = "ACTIVE")
        RoutineStatus status,
        @Schema(description = "반복 유형. 허용값: DAILY(매일), WEEKLY(요일 지정), BIWEEKLY(격주), "
                + "MONTHLY(매월), YEARLY(매년)", example = "WEEKLY")
        String repeatType,
        @Schema(description = "반복 설정. repeatType이 WEEKLY/BIWEEKLY면 요일 목록, "
                + "MONTHLY면 dayOfMonth, YEARLY면 month/day")
        RepeatDays repeatDays,
        @Schema(description = "수행 예정 시각(HH:mm:ss)", example = "07:00:00")
        LocalTime scheduledTime,
        @Schema(description = "시작일(YYYY-MM-DD)", example = "2026-07-01")
        LocalDate startsOn,
        @Schema(description = "종료일(YYYY-MM-DD)", example = "2026-12-31")
        LocalDate endsOn,
        @Schema(description = "버전 계보 루트 ID. 스케줄 수정으로 새 버전(id 변경)이 생겨도 계보 내 값은 불변 — 프론트 목록 key로 사용", example = "1")
        Long originRoutineId,
        @Schema(description = "연동된 집 단체미션 ID(미연동이면 null). 루틴·미션 제목이 바뀌어도 이 값은 유지되므로 이름 매칭 대신 연동 판별에 사용. "
                + "미션 삭제·집 탈퇴/강퇴 시 서버가 자동으로 해제(null)하며, DELETE /api/v1/routines/{id}/house-mission-link 로 직접 해제할 수도 있음", example = "12")
        Long houseMissionId,
        @Schema(description = "기기 캘린더 반복 일정 임포트 출처(예 GOOGLE_CALENDAR). 일반 루틴은 null. 스케줄 수정으로 버전이 갈려도 계보 원본의 값을 그대로 보여준다", example = "GOOGLE_CALENDAR")
        String externalSource,
        @Schema(description = "임포트한 캘린더 반복 일정의 시리즈 id. 일반 루틴은 null", example = "abc123def456@google.com")
        String externalId
) {

    // 외부 참조가 이 row 에 있으면 그대로, 없으면 호출자가 계보 원본(origin)에서 읽어 넘긴 값을 쓴다
    public static RoutineResponse from(Routine routine) {
        return from(routine, routine.getExternalSource(), routine.getExternalId());
    }

    public static RoutineResponse from(Routine routine, String externalSource, String externalId) {
        return new RoutineResponse(
                routine.getId(),
                routine.getTitle(),
                routine.getCategory() != null ? routine.getCategory().getId() : null,
                routine.getAuthType(),
                routine.getStatus(),
                routine.getRepeatType(),
                RepeatDays.fromJson(routine.getRepeatDays()),
                routine.getScheduledTime(),
                routine.getStartsOn(),
                routine.getEndsOn(),
                routine.getOriginRoutineId(),
                routine.getHouseMissionId(),
                externalSource,
                externalId
        );
    }
}
