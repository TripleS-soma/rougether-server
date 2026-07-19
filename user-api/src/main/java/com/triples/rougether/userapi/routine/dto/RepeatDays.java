package com.triples.rougether.userapi.routine.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.routine.error.RoutineErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// 반복 설정. API는 객체로 주고받고, 엔티티에는 JSON 문자열로 저장함(repeat_days JSON 컬럼)
// null 필드는 직렬화에서 제외함(NON_NULL) — 안 그러면 레거시 WEEKLY 저장값({"daysOfWeek":[...]})과
// 새로 직렬화된 값({"daysOfWeek":[...],"dayOfMonth":null,...})의 문자열이 달라져
// RoutineService.isScheduleChanged가 동일 스케줄 수정도 변경으로 오인함
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RepeatDays(
        @Schema(description = "반복 요일 목록. repeatType이 WEEKLY 또는 BIWEEKLY일 때 사용. "
                + "허용값: MON(월), TUE(화), WED(수), THU(목), FRI(금), SAT(토), SUN(일). "
                + "BIWEEKLY는 startsOn이 속한 주를 1주차로 삼아 2주 간격으로 이 요일들에 반복함. "
                + "오늘 현황(GET /api/v1/today)은 기준일의 요일이 이 목록에 포함된 루틴만 대상으로 봄", example = "[\"MON\",\"WED\"]")
        List<String> daysOfWeek,
        @Schema(description = "반복 기준 일(1~31). repeatType이 MONTHLY일 때 사용. "
                + "해당 월에 그 날짜가 없으면(예: 31 지정 + 2월) 그 달은 대상에서 제외됨", example = "15")
        Integer dayOfMonth,
        @Schema(description = "반복 기준 월(1~12). repeatType이 YEARLY일 때 day와 함께 사용", example = "7")
        Integer month,
        @Schema(description = "반복 기준 일(1~31). repeatType이 YEARLY일 때 month와 함께 사용. "
                + "2/29 지정 시 평년에는 대상에서 제외됨", example = "12")
        Integer day
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // WEEKLY/BIWEEKLY 전용 축약 생성자(요일만 지정)
    public RepeatDays(List<String> daysOfWeek) {
        this(daysOfWeek, null, null, null);
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new BusinessException(RoutineErrorCode.REPEAT_DAYS_SERIALIZATION_FAILED);
        }
    }

    public static RepeatDays fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, RepeatDays.class);
        } catch (JsonProcessingException e) {
            // 저장된 JSON이 깨졌어도 조회는 막지 않음(null로 노출)
            return null;
        }
    }
}
