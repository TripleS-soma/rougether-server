package com.triples.rougether.domain.routine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triples.rougether.domain.routine.entity.Routine;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

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
        if ("BIWEEKLY".equalsIgnoreCase(repeatType)) {
            return matchesWeekday(routine.getRepeatDays(), date)
                    && isBiweeklyInterval(routine.getStartsOn(), date);
        }
        if ("MONTHLY".equalsIgnoreCase(repeatType)) {
            return matchesDayOfMonth(routine.getRepeatDays(), date);
        }
        if ("YEARLY".equalsIgnoreCase(repeatType)) {
            return matchesMonthDay(routine.getRepeatDays(), date);
        }
        return false;
    }

    // startsOn이 속한 주(월요일 시작)를 1주차로 삼아 2주 간격인지. isTargetOn에서 이미 date >= startsOn을 보장함
    private static boolean isBiweeklyInterval(LocalDate startsOn, LocalDate date) {
        if (startsOn == null) {
            return false;
        }
        LocalDate startWeekMonday = startsOn.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate dateWeekMonday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long weeksBetween = ChronoUnit.WEEKS.between(startWeekMonday, dateWeekMonday);
        return weeksBetween % 2 == 0;
    }

    // repeat_days JSON의 dayOfMonth와 date의 일(day)이 같은지. 해당 월에 그 날짜가 없으면(예: 31일+2월) date 쪽에서 매칭될 값 자체가 없어 자연히 false
    private static boolean matchesDayOfMonth(String repeatDays, LocalDate date) {
        if (repeatDays == null || repeatDays.isBlank()) {
            return false;
        }
        try {
            JsonNode dayOfMonth = OBJECT_MAPPER.readTree(repeatDays).get("dayOfMonth");
            if (dayOfMonth == null || !dayOfMonth.isInt()) {
                return false;
            }
            return date.getDayOfMonth() == dayOfMonth.asInt();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return false;
        }
    }

    // repeat_days JSON의 month/day와 date의 월/일이 같은지. 윤년 2/29 지정은 평년에 매칭될 날짜가 없어 자연히 false
    private static boolean matchesMonthDay(String repeatDays, LocalDate date) {
        if (repeatDays == null || repeatDays.isBlank()) {
            return false;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(repeatDays);
            JsonNode month = root.get("month");
            JsonNode day = root.get("day");
            if (month == null || day == null || !month.isInt() || !day.isInt()) {
                return false;
            }
            return date.getMonthValue() == month.asInt() && date.getDayOfMonth() == day.asInt();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return false;
        }
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
