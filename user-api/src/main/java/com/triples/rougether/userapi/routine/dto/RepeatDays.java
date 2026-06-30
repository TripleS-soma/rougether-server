package com.triples.rougether.userapi.routine.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

// 반복 설정. API는 객체로 주고받고, 엔티티에는 JSON 문자열로 저장함(repeat_days JSON 컬럼)
public record RepeatDays(
        @Schema(description = "반복 요일(MON~SUN). WEEKLY일 때 사용", example = "[\"MON\",\"WED\"]")
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
