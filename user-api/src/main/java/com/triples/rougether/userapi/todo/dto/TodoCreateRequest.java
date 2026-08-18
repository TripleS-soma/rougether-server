package com.triples.rougether.userapi.todo.dto;

import com.triples.rougether.userapi.global.validation.FiveMinuteStep;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

public record TodoCreateRequest(
        @Schema(description = "투두 제목", example = "장보기")
        @NotBlank @Size(max = 160) String title,
        @Schema(description = "투두 설명", example = "우유, 계란 구매")
        @Size(max = 2000) String description,
        @Schema(description = "소속 카테고리 ID(미지정이면 미분류). 내 카테고리 목록 조회(GET /api/v1/categories) 응답의 id 값", example = "3")
        Long categoryId,
        @Schema(description = "마감일(YYYY-MM-DD). 미지정이면 마감 없음 — 이 경우 오늘 현황(GET /api/v1/today)에는 노출되지 않음", example = "2026-07-01")
        LocalDate dueDate,
        @Schema(description = "마감 시각(HH:mm, 5분 단위만 허용). 미지정이면 시각 없음(날짜만 마감)", example = "18:00:00")
        @FiveMinuteStep LocalTime dueTime,
        @Schema(description = "기기 캘린더 임포트 출처(대문자 영숫자·언더스코어, 최대 30자, 예 GOOGLE_CALENDAR). 서버는 값을 해석하지 않으며 externalId와 반드시 함께 보낸다. 일반 투두는 생략", example = "GOOGLE_CALENDAR")
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,29}$") String externalSource,
        @Schema(description = "그 캘린더의 이벤트 id(최대 255자). 같은 externalSource·externalId 조합은 회원당 한 번만 등록할 수 있으며 사용자가 지운 임포트 투두의 조합도 다시 등록할 수 없다 — 앱은 거절된 조합을 이미 가져온 일정으로 판정해 건너뛴다. externalSource와 반드시 함께 보낸다", example = "abc123def456@google.com")
        @Size(max = 255) @Pattern(regexp = "(?s).*\\S.*", message = "공백만으로는 지정할 수 없습니다") String externalId
) {

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
