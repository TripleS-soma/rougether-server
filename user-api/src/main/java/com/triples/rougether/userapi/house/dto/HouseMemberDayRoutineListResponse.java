package com.triples.rougether.userapi.house.dto;

import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

// 집 멤버의 그날 루틴 + 완료 여부 합친 응답. 오늘 현황(TodayRoutineItem) 스타일에 맞춤.
public record HouseMemberDayRoutineListResponse(
        @Schema(description = "기준 날짜(YYYY-MM-DD). date 미지정 시 오늘(KST)", example = "2026-07-08")
        LocalDate date,
        @Schema(description = "그날 반복 대상 루틴 목록. 수행 예정 시각 오름차순")
        List<MemberRoutineItem> items) {

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
}
