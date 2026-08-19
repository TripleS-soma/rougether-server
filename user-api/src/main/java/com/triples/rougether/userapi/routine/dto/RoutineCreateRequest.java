package com.triples.rougether.userapi.routine.dto;

import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.userapi.global.validation.FiveMinuteStep;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

public record RoutineCreateRequest(
        @Schema(description = "루틴 제목", example = "아침 운동")
        @NotBlank @Size(max = 160) String title,
        @Schema(description = "소속 카테고리 ID(미지정이면 미분류). 내 카테고리 목록 조회(GET /api/v1/categories) 응답의 id 값", example = "3")
        Long categoryId,
        @Schema(description = "인증 방식. 허용값: CHECK(체크형), PHOTO(사진 인증형)", example = "CHECK")
        @NotNull AuthType authType,
        @Schema(description = "반복 유형. 허용값: DAILY(매일), WEEKLY(요일 지정 — repeatDays 필요), "
                + "BIWEEKLY(격주 — repeatDays.daysOfWeek + startsOn 필요), "
                + "MONTHLY(매월 — repeatDays.dayOfMonth 필요), "
                + "YEARLY(매년 — repeatDays.month/day 필요)", example = "WEEKLY")
        @NotBlank @Pattern(regexp = "DAILY|WEEKLY|BIWEEKLY|MONTHLY|YEARLY") String repeatType,
        @Schema(description = "반복 설정. repeatType이 WEEKLY/BIWEEKLY면 daysOfWeek, "
                + "MONTHLY면 dayOfMonth, YEARLY면 month/day 지정. DAILY면 생략")
        RepeatDays repeatDays,
        @Schema(description = "수행 예정 시각(HH:mm, 5분 단위만 허용). 루틴 목록·오늘 현황의 정렬 기준, 미지정 가능", example = "07:00:00")
        @FiveMinuteStep LocalTime scheduledTime,
        @Schema(description = "시작일(YYYY-MM-DD). 미지정이면 생성일(오늘)로 지정됨. 오늘 이전 과거일은 불가. 오늘 현황은 시작일~종료일 범위의 루틴만 대상으로 봄", example = "2026-07-01")
        LocalDate startsOn,
        @Schema(description = "종료일(YYYY-MM-DD). 미지정이면 제한 없음. 시작일보다 앞설 수 없음", example = "2026-12-31")
        LocalDate endsOn,
        @Schema(description = "연동할 집 단체미션 ID(선택, 미지정이면 미연동). 집 미션 목록(GET /api/v1/houses/{houseId}/missions) 응답의 id 값. "
                + "해당 미션이 있는 집의 활성 구성원만 지정할 수 있으며, 지정하면 응답의 houseMissionId 로 연동 여부를 표시함", example = "12")
        Long houseMissionId,
        @Schema(description = "기기 캘린더 반복 일정 임포트 출처(대문자 영숫자·언더스코어, 최대 30자, 예 GOOGLE_CALENDAR). 서버는 값을 해석하지 않으며 externalId와 반드시 함께 보낸다. 일반 루틴은 생략", example = "GOOGLE_CALENDAR")
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,29}$") String externalSource,
        @Schema(description = "그 캘린더 반복 일정의 시리즈 id(최대 255자, 회차 id 아님 — 반복 규칙은 앱이 완성해 repeatType·repeatDays로 보낸다). 같은 externalSource·externalId 조합은 회원당 한 번만 등록할 수 있으며 지운·스케줄을 바꾼 임포트 루틴의 조합도 다시 등록할 수 없다 — 앱은 거절된 조합을 이미 가져온 일정으로 판정해 건너뛴다. externalSource와 반드시 함께 보낸다", example = "abc123def456@google.com")
        @Size(max = 255) @Pattern(regexp = "(?s).*\\S.*", message = "공백만으로는 지정할 수 없습니다") String externalId
) {

    // 외부 참조는 앞뒤 공백을 떼고 검증·저장한다 — @Size/@Pattern 이 trim 된 값을 보도록 역직렬화 직후 정리
    public RoutineCreateRequest {
        externalSource = externalSource == null ? null : externalSource.trim();
        externalId = externalId == null ? null : externalId.trim();
    }

    // 기존 클라이언트/테스트 호환용 (houseMissionId 미지정 = 미연동)
    public RoutineCreateRequest(String title, Long categoryId, AuthType authType, String repeatType,
                                RepeatDays repeatDays, LocalTime scheduledTime,
                                LocalDate startsOn, LocalDate endsOn) {
        this(title, categoryId, authType, repeatType, repeatDays, scheduledTime, startsOn, endsOn, null, null, null);
    }

    // 기존 클라이언트/테스트 호환용 (외부 참조 미지정 = 일반 루틴)
    public RoutineCreateRequest(String title, Long categoryId, AuthType authType, String repeatType,
                                RepeatDays repeatDays, LocalTime scheduledTime,
                                LocalDate startsOn, LocalDate endsOn, Long houseMissionId) {
        this(title, categoryId, authType, repeatType, repeatDays, scheduledTime, startsOn, endsOn, houseMissionId,
                null, null);
    }

    // 임포트 요청인지(둘 다 채워짐). 한쪽만 채워진 불완전 요청은 서비스에서 400 처리
    public boolean hasExternalRef() {
        return hasText(externalSource) && hasText(externalId);
    }

    public boolean hasPartialExternalRef() {
        return hasText(externalSource) != hasText(externalId);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
