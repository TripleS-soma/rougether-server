package com.triples.rougether.common.i18n;

import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class RecommendationMessage {
    private RecommendationMessage() {}

    public static String render(String code, Map<String, Object> params, Language language, String fallback) {
        if (language != Language.EN || code == null || params == null || !(params.get("title") instanceof String)) {
            return fallback;
        }
        if ("ADJUST_DROP_DAY".equals(code) && !(params.get("day") instanceof String)) {
            return fallback;
        }
        if ("ADJUST_REDUCE_DAYS".equals(code)
                && (!(params.get("days") instanceof List<?> days)
                    || days.isEmpty() || days.stream().anyMatch(value -> !(value instanceof String))
                    || !(params.get("to") instanceof Number))) {
            return fallback;
        }
        return switch (code) {
            case "ADJUST_DROP_DAY" -> "“%s” was missed on %s for three weeks in a row. How about focusing on the other days?"
                    .formatted(params.get("title"), day((String) params.get("day")));
            case "ADJUST_REDUCE_DAYS" -> "“%s” has been below 40%% completion for two weeks. Try %s (%s times a week) to rebuild your rhythm."
                    .formatted(params.get("title"), ((List<?>) params.get("days")).stream()
                            .map(value -> day(value.toString())).collect(Collectors.joining(", ")), params.get("to"));
            default -> fallback;
        };
    }

    private static String day(String token) {
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day.name().startsWith(token)) { return day.getDisplayName(TextStyle.FULL, Locale.ENGLISH); }
        }
        return token;
    }
}
