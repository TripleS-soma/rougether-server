package com.triples.rougether.domain.routine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triples.rougether.domain.routine.entity.Routine;
import java.time.LocalDate;

// 루틴 반복규칙(starts_on~ends_on, repeat_type, repeat_days) 판정. today/calendar/house 조회가 공유함
public final class RoutineRecurrence {

    // repeat_days JSON 파싱 전용. domain 컨텍스트에 ObjectMapper 빈이 없어 자체 인스턴스 사용함
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RoutineRecurrence() {
    }

    // starts_on~ends_on 범위 안이고 repeat 규칙이 date에 맞으면 대상
    public static boolean isTargetOn(Routine routine, LocalDate date) {
        if (routine.getStartsOn() != null && date.isBefore(routine.getStartsOn())) {
            return false;
        }
        if (routine.getEndsOn() != null && date.isAfter(routine.getEndsOn())) {
            return false;
        }
        String repeatType = routine.getRepeatType();
        if (repeatType == null) {
            return false;
        }
        if ("DAILY".equalsIgnoreCase(repeatType)) {
            return true;
        }
        if ("WEEKLY".equalsIgnoreCase(repeatType)) {
            return matchesWeekday(routine.getRepeatDays(), date);
        }
        return false;
    }

    // repeat_days JSON의 daysOfWeek에 date의 요일(MON~SUN)이 포함되는지
    private static boolean matchesWeekday(String repeatDays, LocalDate date) {
        if (repeatDays == null || repeatDays.isBlank()) {
            return false;
        }
        // MONDAY → MON (저장 토큰과 동일 형태)
        String token = date.getDayOfWeek().name().substring(0, 3);
        try {
            JsonNode days = OBJECT_MAPPER.readTree(repeatDays).get("daysOfWeek");
            if (days == null || !days.isArray()) {
                return false;
            }
            for (JsonNode day : days) {
                if (token.equalsIgnoreCase(day.asText())) {
                    return true;
                }
            }
            return false;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return false;
        }
    }
}
