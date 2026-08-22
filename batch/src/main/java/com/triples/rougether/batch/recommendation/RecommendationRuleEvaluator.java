package com.triples.rougether.batch.recommendation;

import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// 조정 추천 룰 엔진(#329). DB·시계 접근 없는 순수 판정 — 입력(현재 ACTIVE 루틴, 계보별 3주 log, 주 경계)만으로
// 제안을 만든다. 계보당 최대 1건(룰 1 우선), 결과는 우선순위(룰 1 → 3주 실패 수 내림차순 → 계보 id 오름차순) 정렬.
@Component
public class RecommendationRuleEvaluator {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    // 요일 정렬·토큰 변환의 정본 순서(MON..SUN — repeat_days 토큰과 동일 표기)
    private static final List<DayOfWeek> CANONICAL_DAYS = List.of(DayOfWeek.values());

    // 일~토 한 주. weeks 는 오래된 주 → 대상 주 순 3개
    public record WeekRange(LocalDate start, LocalDate end) {
        boolean contains(LocalDate date) {
            return !date.isBefore(start) && !date.isAfter(end);
        }
    }

    // 제안 1건. originRoutineId = 계보 루트, routineId = 생성 시점 대상 버전(현재 ACTIVE 버전 id)
    public record Proposal(Long originRoutineId, Long routineId, String repeatType, List<String> daysOfWeek,
                           String message) {
    }

    public List<Proposal> evaluate(List<Routine> activeRoutines, Map<Long, List<RoutineLog>> logsByLineage,
                                   List<WeekRange> weeks) {
        List<Ranked> ranked = new ArrayList<>();
        for (Routine routine : activeRoutines) {
            Long lineageId = lineageKey(routine);
            List<RoutineLog> logs = logsByLineage.getOrDefault(lineageId, List.of());
            if (logs.isEmpty()) {
                continue;
            }
            LineageStats stats = LineageStats.of(logs, weeks);
            Ranked proposal = evaluateFailDayDrop(routine, lineageId, stats, weeks.size());
            if (proposal == null) {
                proposal = evaluateFrequencyReduce(routine, lineageId, stats, weeks.size());
            }
            if (proposal != null) {
                ranked.add(proposal);
            }
        }
        ranked.sort(Comparator.comparingInt(Ranked::rulePriority)
                .thenComparing(Comparator.comparingInt(Ranked::totalFailed).reversed())
                .thenComparing(r -> r.proposal().originRoutineId()));
        return ranked.stream().map(Ranked::proposal).toList();
    }

    // 룰 1 — 실패 요일 제외: 요일 2개 이상 WEEKLY 에서 어떤 요일이 3주 매주 FAILED(그 요일 완료 0)이고
    // 나머지 요일 완료율이 기준 이상이면 그 요일 하나(실패 최다, 동률이면 요일 순)를 뺀 daysOfWeek 를 제안
    private Ranked evaluateFailDayDrop(Routine routine, Long lineageId, LineageStats stats, int weekCount) {
        if (!"WEEKLY".equalsIgnoreCase(routine.getRepeatType())) {
            return null;
        }
        List<DayOfWeek> scheduledDays = parseDaysOfWeek(routine.getRepeatDays());
        if (scheduledDays.size() < 2) {
            return null;
        }
        DayOfWeek worst = null;
        for (DayOfWeek day : scheduledDays) {
            if (stats.completed(day) > 0 || stats.failedWeekCount(day) < weekCount) {
                continue;
            }
            int otherCompleted = stats.totalCompleted() - stats.completed(day);
            int otherFailed = stats.totalFailed() - stats.failed(day);
            int otherTotal = otherCompleted + otherFailed;
            if (otherTotal == 0 || (double) otherCompleted / otherTotal < RecommendationPolicy.OTHER_DAYS_COMPLETION_MIN) {
                continue;
            }
            if (worst == null || stats.failed(day) > stats.failed(worst)) {
                worst = day;
            }
        }
        if (worst == null) {
            return null;
        }
        DayOfWeek dropped = worst;
        List<String> remaining = scheduledDays.stream()
                .filter(day -> day != dropped)
                .sorted(Comparator.comparingInt(CANONICAL_DAYS::indexOf))
                .map(RecommendationRuleEvaluator::token)
                .toList();
        String message = "『%s』 %s요일 수행이 3주 연속 실패했어요. %s요일을 빼고 나머지 요일에 집중해 보면 어떨까요?"
                .formatted(routine.getTitle(), label(dropped), label(dropped));
        return new Ranked(0, stats.totalFailed(),
                new Proposal(lineageId, routine.getId(), "WEEKLY", remaining, message));
    }

    // 룰 2 — 빈도 축소: DAILY 또는 요일 5개 이상 WEEKLY 가 직전 2주 각각 완료율 기준 미만이면
    // 3주 완료율 상위 요일 최대 3개(완료율 → 완료 수 → 요일 순)로 WEEKLY 전환을 제안
    private Ranked evaluateFrequencyReduce(Routine routine, Long lineageId, LineageStats stats, int weekCount) {
        boolean daily = "DAILY".equalsIgnoreCase(routine.getRepeatType());
        List<DayOfWeek> scheduledDays = daily ? List.of() : parseDaysOfWeek(routine.getRepeatDays());
        boolean weekly = "WEEKLY".equalsIgnoreCase(routine.getRepeatType())
                && scheduledDays.size() >= RecommendationPolicy.REDUCE_TARGET_MIN_DAYS;
        if (!daily && !weekly) {
            return null;
        }
        // 직전 2주 = 근거 창의 마지막 2개 주. 두 주 모두 수행 기록이 있어야 판정 성립(없으면 근거 부족으로 미생성)
        for (int week = weekCount - 2; week < weekCount; week++) {
            int total = stats.weekCompleted(week) + stats.weekFailed(week);
            if (total == 0 || (double) stats.weekCompleted(week) / total >= RecommendationPolicy.LOW_COMPLETION_MAX) {
                return null;
            }
        }
        List<DayOfWeek> completedDays = CANONICAL_DAYS.stream()
                .filter(day -> stats.completed(day) > 0)
                .toList();
        if (completedDays.size() < RecommendationPolicy.REDUCE_MIN_COMPLETED_WEEKDAYS) {
            return null;
        }
        List<DayOfWeek> selected = completedDays.stream()
                .sorted(Comparator.comparingDouble((DayOfWeek day) -> stats.completionRate(day)).reversed()
                        .thenComparing(Comparator.comparingInt((DayOfWeek day) -> stats.completed(day)).reversed())
                        .thenComparing(CANONICAL_DAYS::indexOf))
                .limit(RecommendationPolicy.REDUCE_PROPOSED_MAX_DAYS)
                .sorted(Comparator.comparingInt(CANONICAL_DAYS::indexOf))
                .toList();
        List<String> tokens = selected.stream().map(RecommendationRuleEvaluator::token).toList();
        StringJoiner labels = new StringJoiner("·");
        selected.forEach(day -> labels.add(label(day)));
        String message = "『%s』 완료율이 2주째 40%%를 밑돌았어요. 완료가 잘 되던 %s요일, 주 %d회로 줄여 리듬을 되찾아 보면 어떨까요?"
                .formatted(routine.getTitle(), labels, selected.size());
        return new Ranked(1, stats.totalFailed(),
                new Proposal(lineageId, routine.getId(), "WEEKLY", tokens, message));
    }

    static Long lineageKey(Routine routine) {
        return routine.getOriginRoutineId() != null ? routine.getOriginRoutineId() : routine.getId();
    }

    // repeat_days JSON 의 daysOfWeek → DayOfWeek 목록. 깨졌거나 형식이 다르면 빈 목록(그 루틴은 자연 제외)
    static List<DayOfWeek> parseDaysOfWeek(String repeatDaysJson) {
        if (repeatDaysJson == null || repeatDaysJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode days = MAPPER.readTree(repeatDaysJson).get("daysOfWeek");
            if (days == null || !days.isArray()) {
                return List.of();
            }
            Set<DayOfWeek> parsed = new HashSet<>();
            for (JsonNode dayNode : days) {
                DayOfWeek day = fromToken(dayNode.asString());
                if (day != null) {
                    parsed.add(day);
                }
            }
            return CANONICAL_DAYS.stream().filter(parsed::contains).toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    static String token(DayOfWeek day) {
        return day.name().substring(0, 3);
    }

    private static DayOfWeek fromToken(String token) {
        if (token == null) {
            return null;
        }
        String normalized = token.strip().toUpperCase(Locale.ROOT);
        for (DayOfWeek day : CANONICAL_DAYS) {
            if (token(day).equals(normalized)) {
                return day;
            }
        }
        return null;
    }

    static String label(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "월";
            case TUESDAY -> "화";
            case WEDNESDAY -> "수";
            case THURSDAY -> "목";
            case FRIDAY -> "금";
            case SATURDAY -> "토";
            case SUNDAY -> "일";
        };
    }

    private record Ranked(int rulePriority, int totalFailed, Proposal proposal) {
    }

    // 계보 1개의 3주 집계. 요일별 완료/실패, 요일별 실패가 있었던 주 수, 주별 완료/실패
    private static final class LineageStats {

        private final Map<DayOfWeek, Integer> completedByDay = new EnumMap<>(DayOfWeek.class);
        private final Map<DayOfWeek, Integer> failedByDay = new EnumMap<>(DayOfWeek.class);
        private final Map<DayOfWeek, Set<Integer>> failedWeeksByDay = new EnumMap<>(DayOfWeek.class);
        private final int[] weekCompleted;
        private final int[] weekFailed;
        private int totalCompleted;
        private int totalFailed;

        private LineageStats(int weekCount) {
            this.weekCompleted = new int[weekCount];
            this.weekFailed = new int[weekCount];
        }

        static LineageStats of(List<RoutineLog> logs, List<WeekRange> weeks) {
            LineageStats stats = new LineageStats(weeks.size());
            for (RoutineLog routineLog : logs) {
                int week = weekIndexOf(routineLog.getRoutineDate(), weeks);
                if (week < 0) {
                    continue;
                }
                DayOfWeek day = routineLog.getRoutineDate().getDayOfWeek();
                if (routineLog.getStatus() == RoutineLogStatus.COMPLETED) {
                    stats.completedByDay.merge(day, 1, Integer::sum);
                    stats.weekCompleted[week]++;
                    stats.totalCompleted++;
                } else if (routineLog.getStatus() == RoutineLogStatus.FAILED) {
                    stats.failedByDay.merge(day, 1, Integer::sum);
                    stats.failedWeeksByDay.computeIfAbsent(day, key -> new HashSet<>()).add(week);
                    stats.weekFailed[week]++;
                    stats.totalFailed++;
                }
            }
            return stats;
        }

        private static int weekIndexOf(LocalDate date, List<WeekRange> weeks) {
            for (int i = 0; i < weeks.size(); i++) {
                if (weeks.get(i).contains(date)) {
                    return i;
                }
            }
            return -1;
        }

        int completed(DayOfWeek day) {
            return completedByDay.getOrDefault(day, 0);
        }

        int failed(DayOfWeek day) {
            return failedByDay.getOrDefault(day, 0);
        }

        int failedWeekCount(DayOfWeek day) {
            return failedWeeksByDay.getOrDefault(day, Set.of()).size();
        }

        double completionRate(DayOfWeek day) {
            int total = completed(day) + failed(day);
            return total == 0 ? 0.0 : (double) completed(day) / total;
        }

        int weekCompleted(int week) {
            return weekCompleted[week];
        }

        int weekFailed(int week) {
            return weekFailed[week];
        }

        int totalCompleted() {
            return totalCompleted;
        }

        int totalFailed() {
            return totalFailed;
        }
    }
}
