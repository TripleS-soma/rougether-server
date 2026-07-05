package com.triples.rougether.userapi.routine.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// 반복 설정. API는 객체로 주고받고, 엔티티에는 JSON 문자열로 저장함(repeat_days JSON 컬럼)
public record RepeatDays(
        @Schema(description = "반복 요일 목록. repeatType이 WEEKLY일 때 사용. "
                + "허용값: MON(월), TUE(화), WED(수), THU(목), FRI(금), SAT(토), SUN(일). "
                + "오늘 현황(GET /api/v1/today)은 기준일의 요일이 이 목록에 포함된 루틴만 대상으로 봄", example = "[\"MON\",\"WED\"]")
        List<String> daysOfWeek
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("repeatDays 직렬화 실패", e);
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
