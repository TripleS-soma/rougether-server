package com.triples.rougether.batch.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.batch.recommendation.RecommendationRuleEvaluator.Proposal;
import com.triples.rougether.batch.recommendation.RecommendationRuleEvaluator.WeekRange;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.shared.CurrencyType;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

// 순수 룰 판정 검증. 주 경계는 일~토, weekStart(2025-03-09 일요일)를 대상 주로 하는 3주 창.
class RecommendationRuleEvaluatorTest {

    private static final LocalDate WEEK_START = LocalDate.of(2025, 3, 9);
    private static final List<WeekRange> WEEKS = RoutineRecommendationJobConfig.weeksOf(WEEK_START);

    private final RecommendationRuleEvaluator evaluator = new RecommendationRuleEvaluator();

    // ---------- 룰 1: 실패 요일 제외 ----------

    @Test
    void 한_요일이_3주_매주_실패하고_나머지_완료율이_좋으면_그_요일을_뺀_제안을_만든다() {
        Routine routine = weekly(1L, "아침 러닝", "MON", "WED", "FRI");
        List<RoutineLog> logs = new ArrayList<>();
        for (int week = 0; week < 3; week++) {
            logs.add(log(routine, dateOf(week, DayOfWeek.MONDAY), RoutineLogStatus.COMPLETED));
            logs.add(log(routine, dateOf(week, DayOfWeek.WEDNESDAY), RoutineLogStatus.FAILED));
            logs.add(log(routine, dateOf(week, DayOfWeek.FRIDAY), RoutineLogStatus.COMPLETED));
        }

        List<Proposal> proposals = evaluator.evaluate(List.of(routine), byLineage(logs), WEEKS);

        assertThat(proposals).hasSize(1);
        Proposal proposal = proposals.getFirst();
        assertThat(proposal.originRoutineId()).isEqualTo(1L);
        assertThat(proposal.routineId()).isEqualTo(1L);
        assertThat(proposal.repeatType()).isEqualTo("WEEKLY");
        assertThat(proposal.daysOfWeek()).containsExactly("MON", "FRI");
        assertThat(proposal.message()).contains("아침 러닝").contains("수요일");
    }

    @Test
    void 실패가_2주뿐이면_실패_요일_제외를_제안하지_않는다() {
        Routine routine = weekly(1L, "러닝", "MON", "WED");
        List<RoutineLog> logs = new ArrayList<>();
        for (int week = 0; week < 3; week++) {
            logs.add(log(routine, dateOf(week, DayOfWeek.MONDAY), RoutineLogStatus.COMPLETED));
        }
        logs.add(log(routine, dateOf(1, DayOfWeek.WEDNESDAY), RoutineLogStatus.FAILED));
        logs.add(log(routine, dateOf(2, DayOfWeek.WEDNESDAY), RoutineLogStatus.FAILED));

        assertThat(evaluator.evaluate(List.of(routine), byLineage(logs), WEEKS)).isEmpty();
    }

    @Test
    void 실패_요일에_완료가_한_번이라도_있으면_제안하지_않는다() {
        Routine routine = weekly(1L, "러닝", "MON", "WED");
        List<RoutineLog> logs = new ArrayList<>();
        for (int week = 0; week < 3; week++) {
            logs.add(log(routine, dateOf(week, DayOfWeek.MONDAY), RoutineLogStatus.COMPLETED));
            logs.add(log(routine, dateOf(week, DayOfWeek.WEDNESDAY), RoutineLogStatus.FAILED));
        }
        logs.add(log(routine, dateOf(0, DayOfWeek.WEDNESDAY).plusWeeks(0), RoutineLogStatus.COMPLETED));

        assertThat(evaluator.evaluate(List.of(routine), byLineage(logs), WEEKS)).isEmpty();
    }

    @Test
    void 나머지_요일_완료율이_50퍼센트_미만이면_제안하지_않는다() {
        Routine routine = weekly(1L, "러닝", "MON", "WED", "FRI");
        List<RoutineLog> logs = new ArrayList<>();
        for (int week = 0; week < 3; week++) {
            logs.add(log(routine, dateOf(week, DayOfWeek.WEDNESDAY), RoutineLogStatus.FAILED));
            logs.add(log(routine, dateOf(week, DayOfWeek.MONDAY), RoutineLogStatus.FAILED));
            logs.add(log(routine, dateOf(week, DayOfWeek.FRIDAY),
                    week == 0 ? RoutineLogStatus.COMPLETED : RoutineLogStatus.FAILED));
        }

        // 나머지(월·금) 완료 1 / 실패 5 → 17% < 50%. 월요일도 3주 실패지만 같은 이유로 탈락
        assertThat(evaluator.evaluate(List.of(routine), byLineage(logs), WEEKS)).isEmpty();
    }

    @Test
    void 요일이_하나뿐인_WEEKLY는_실패_요일_제외_대상이_아니다() {
        Routine routine = weekly(1L, "러닝", "WED");
        List<RoutineLog> logs = new ArrayList<>();
        for (int week = 0; week < 3; week++) {
            logs.add(log(routine, dateOf(week, DayOfWeek.WEDNESDAY), RoutineLogStatus.FAILED));
        }

        assertThat(evaluator.evaluate(List.of(routine), byLineage(logs), WEEKS)).isEmpty();
    }

    @Test
    void 실패_요일이_여럿이면_실패_수가_가장_많은_요일_하나만_뺀다() {
        // 월·수 둘 다 3주 연속 실패 후보가 되도록 완료를 목·금·토에 몰아준다(나머지 완료율 조건 충족)
        Routine routine = weekly(1L, "러닝", "MON", "WED", "THU", "FRI", "SAT");
        List<RoutineLog> logs = new ArrayList<>();
        for (int week = 0; week < 3; week++) {
            logs.add(log(routine, dateOf(week, DayOfWeek.MONDAY), RoutineLogStatus.FAILED));
            logs.add(log(routine, dateOf(week, DayOfWeek.WEDNESDAY), RoutineLogStatus.FAILED));
            logs.add(log(routine, dateOf(week, DayOfWeek.THURSDAY), RoutineLogStatus.COMPLETED));
            logs.add(log(routine, dateOf(week, DayOfWeek.FRIDAY), RoutineLogStatus.COMPLETED));
            logs.add(log(routine, dateOf(week, DayOfWeek.SATURDAY), RoutineLogStatus.COMPLETED));
        }
        // 수요일에 실패 1회 추가(주중 재실패) → 수요일이 실패 최다
        logs.add(log(routine, dateOf(0, DayOfWeek.WEDNESDAY), RoutineLogStatus.FAILED));

        List<Proposal> proposals = evaluator.evaluate(List.of(routine), byLineage(logs), WEEKS);

        assertThat(proposals).hasSize(1);
        assertThat(proposals.getFirst().daysOfWeek()).containsExactly("MON", "THU", "FRI", "SAT");
    }

    // ---------- 룰 2: 빈도 축소 ----------

    @Test
    void DAILY가_2주_연속_저조하면_완료율_상위_요일로_WEEKLY_전환을_제안한다() {
        Routine routine = daily(1L, "독서");
        List<RoutineLog> logs = new ArrayList<>();
        // 첫 주: 성한 기록(완료율 계산에만 기여)
        logs.add(log(routine, dateOf(0, DayOfWeek.MONDAY), RoutineLogStatus.COMPLETED));
        logs.add(log(routine, dateOf(0, DayOfWeek.TUESDAY), RoutineLogStatus.COMPLETED));
        logs.add(log(routine, dateOf(0, DayOfWeek.WEDNESDAY), RoutineLogStatus.COMPLETED));
        // 직전 2주: 각각 완료 2/7 = 29% < 40%
        for (int week = 1; week < 3; week++) {
            for (DayOfWeek day : DayOfWeek.values()) {
                boolean completed = day == DayOfWeek.MONDAY || day == DayOfWeek.TUESDAY;
                logs.add(log(routine, dateOf(week, day),
                        completed ? RoutineLogStatus.COMPLETED : RoutineLogStatus.FAILED));
            }
        }

        List<Proposal> proposals = evaluator.evaluate(List.of(routine), byLineage(logs), WEEKS);

        assertThat(proposals).hasSize(1);
        Proposal proposal = proposals.getFirst();
        assertThat(proposal.repeatType()).isEqualTo("WEEKLY");
        // 완료율: 월 3/3, 화 3/3, 수 1/3 → 상위 3개 = 월·화·수 (월·화 동률은 완료 수도 동률이라 요일 순)
        assertThat(proposal.daysOfWeek()).containsExactly("MON", "TUE", "WED");
        assertThat(proposal.message()).contains("독서").contains("월·화·수").contains("주 3회");
    }

    @Test
    void 직전_2주_중_한_주라도_완료율_40퍼센트_이상이면_축소를_제안하지_않는다() {
        Routine routine = daily(1L, "독서");
        List<RoutineLog> logs = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            // 가운데 주: 3/7 = 43% >= 40%
            boolean completed = day == DayOfWeek.MONDAY || day == DayOfWeek.TUESDAY || day == DayOfWeek.WEDNESDAY;
            logs.add(log(routine, dateOf(1, day), completed ? RoutineLogStatus.COMPLETED : RoutineLogStatus.FAILED));
            // 대상 주: 1/7 = 14%
            logs.add(log(routine, dateOf(2, day),
                    day == DayOfWeek.MONDAY ? RoutineLogStatus.COMPLETED : RoutineLogStatus.FAILED));
        }

        assertThat(evaluator.evaluate(List.of(routine), byLineage(logs), WEEKS)).isEmpty();
    }

    @Test
    void 완료율이_정확히_40퍼센트인_주는_저조로_보지_않는다() {
        // 요일 5개 WEEKLY, 직전 2주 각각 완료 2/5 = 정확히 40% → 미만이 아니라 탈락(경계 배타)
        Routine routine = weekly(1L, "경계", "MON", "TUE", "WED", "THU", "FRI");
        List<RoutineLog> logs = new ArrayList<>();
        for (int week = 1; week < 3; week++) {
            for (DayOfWeek day : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)) {
                logs.add(log(routine, dateOf(week, day), RoutineLogStatus.COMPLETED));
            }
            for (DayOfWeek day : List.of(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
                logs.add(log(routine, dateOf(week, day), RoutineLogStatus.FAILED));
            }
        }

        // 룰 1도 불성립(실패 요일들이 2주뿐) → 아무 제안 없음
        assertThat(evaluator.evaluate(List.of(routine), byLineage(logs), WEEKS)).isEmpty();
    }

    @Test
    void 완료_이력_요일이_2개_미만이면_축소를_제안하지_않는다() {
        Routine routine = daily(1L, "독서");
        List<RoutineLog> logs = new ArrayList<>();
        for (int week = 1; week < 3; week++) {
            for (DayOfWeek day : DayOfWeek.values()) {
                logs.add(log(routine, dateOf(week, day),
                        day == DayOfWeek.MONDAY ? RoutineLogStatus.COMPLETED : RoutineLogStatus.FAILED));
            }
        }

        assertThat(evaluator.evaluate(List.of(routine), byLineage(logs), WEEKS)).isEmpty();
    }

    @Test
    void 요일_4개_이하_WEEKLY는_축소_대상이_아니다() {
        Routine routine = weekly(1L, "독서", "MON", "TUE", "WED", "THU");
        List<RoutineLog> logs = new ArrayList<>();
        for (int week = 1; week < 3; week++) {
            logs.add(log(routine, dateOf(week, DayOfWeek.MONDAY), RoutineLogStatus.COMPLETED));
            logs.add(log(routine, dateOf(week, DayOfWeek.TUESDAY), RoutineLogStatus.COMPLETED));
            logs.add(log(routine, dateOf(week, DayOfWeek.WEDNESDAY), RoutineLogStatus.FAILED));
            logs.add(log(routine, dateOf(week, DayOfWeek.THURSDAY), RoutineLogStatus.FAILED));
        }

        // 주 완료율 50% >= 40% 이기도 하지만, 대상 조건(요일 5개 이상) 자체가 성립하지 않는다
        assertThat(evaluator.evaluate(List.of(routine), byLineage(logs), WEEKS)).isEmpty();
    }

    @Test
    void 축소_제안_요일은_완료율과_요일순_타이브레이크로_상위_3개를_고른다() {
        Routine routine = daily(1L, "독서");
        List<RoutineLog> logs = new ArrayList<>();
        // 첫 주(자유 주)는 부분 기록: 토·금·월 완료 → 완료율 분모에만 기여
        logs.add(log(routine, dateOf(0, DayOfWeek.SATURDAY), RoutineLogStatus.COMPLETED));
        logs.add(log(routine, dateOf(0, DayOfWeek.FRIDAY), RoutineLogStatus.COMPLETED));
        logs.add(log(routine, dateOf(0, DayOfWeek.MONDAY), RoutineLogStatus.COMPLETED));
        // 직전 2주: 각각 토·화만 완료(2/7 = 29% < 40%), 나머지 실패
        for (int week = 1; week < 3; week++) {
            for (DayOfWeek day : DayOfWeek.values()) {
                boolean completed = day == DayOfWeek.SATURDAY || day == DayOfWeek.TUESDAY;
                logs.add(log(routine, dateOf(week, day),
                        completed ? RoutineLogStatus.COMPLETED : RoutineLogStatus.FAILED));
            }
        }

        List<Proposal> proposals = evaluator.evaluate(List.of(routine), byLineage(logs), WEEKS);

        assertThat(proposals).hasSize(1);
        // 완료율: 토 3/3(1.0) · 화 2/2(1.0) > 월 1/3(0.33) = 금 1/3(0.33) > 나머지 0.
        // 3번째 자리는 월·금 완료율 동률(완료 수도 동률) → 요일 순으로 월. 최종 표기는 요일 순 정렬.
        assertThat(proposals.getFirst().daysOfWeek()).containsExactly("MON", "TUE", "SAT");
    }

    // ---------- 공통 가드·우선순위 ----------

    @Test
    void BIWEEKLY_MONTHLY_YEARLY는_어느_룰의_대상도_아니다() {
        Routine biweekly = routine(1L, "격주", "BIWEEKLY", "{\"daysOfWeek\":[\"MON\",\"WED\"]}");
        Routine monthly = routine(2L, "월간", "MONTHLY", "{\"dayOfMonth\":15}");
        List<RoutineLog> logs = new ArrayList<>();
        for (int week = 0; week < 3; week++) {
            logs.add(log(biweekly, dateOf(week, DayOfWeek.WEDNESDAY), RoutineLogStatus.FAILED));
            logs.add(log(biweekly, dateOf(week, DayOfWeek.MONDAY), RoutineLogStatus.COMPLETED));
            logs.add(log(monthly, dateOf(week, DayOfWeek.SATURDAY), RoutineLogStatus.FAILED));
        }

        assertThat(evaluator.evaluate(List.of(biweekly, monthly), byLineage(logs), WEEKS)).isEmpty();
    }

    @Test
    void repeat_days가_깨진_WEEKLY는_자연_제외된다() {
        Routine broken = routine(1L, "깨짐", "WEEKLY", "{broken json");
        List<RoutineLog> logs = new ArrayList<>();
        for (int week = 0; week < 3; week++) {
            logs.add(log(broken, dateOf(week, DayOfWeek.WEDNESDAY), RoutineLogStatus.FAILED));
        }

        assertThat(evaluator.evaluate(List.of(broken), byLineage(logs), WEEKS)).isEmpty();
    }

    @Test
    void 두_룰에_모두_해당하면_계보당_실패_요일_제외_하나만_만든다() {
        // 요일 5개 WEEKLY. 수요일은 3주 매주 실패·완료 0(룰 1 후보), 직전 2주는 주 완료율 1/5=20%<40%(룰 2 후보).
        // 첫 주에 나머지 요일 완료를 몰아줘 룰 1 의 "나머지 요일 완료율" 을 정확히 50% 경계로 충족시킨다.
        Routine routine = weekly(1L, "복합", "MON", "TUE", "WED", "THU", "FRI");
        List<RoutineLog> logs = new ArrayList<>();
        // 첫 주: 월·화·목·금 완료, 수 실패
        for (DayOfWeek day : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
            logs.add(log(routine, dateOf(0, day), RoutineLogStatus.COMPLETED));
        }
        logs.add(log(routine, dateOf(0, DayOfWeek.WEDNESDAY), RoutineLogStatus.FAILED));
        // 직전 2주: 월만 완료, 화·수·목·금 실패
        for (int week = 1; week < 3; week++) {
            logs.add(log(routine, dateOf(week, DayOfWeek.MONDAY), RoutineLogStatus.COMPLETED));
            for (DayOfWeek day : List.of(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                    DayOfWeek.FRIDAY)) {
                logs.add(log(routine, dateOf(week, day), RoutineLogStatus.FAILED));
            }
        }

        List<Proposal> proposals = evaluator.evaluate(List.of(routine), byLineage(logs), WEEKS);

        // 나머지(월·화·목·금) 완료 6/12 = 정확히 50% → 룰 1 성립(경계 포함). 룰 2 조건도 참이지만 룰 1 이 우선.
        assertThat(proposals).hasSize(1);
        assertThat(proposals.getFirst().daysOfWeek()).containsExactly("MON", "TUE", "THU", "FRI");
    }

    @Test
    void 우선순위는_실패_요일_제외가_먼저고_같은_룰이면_실패_수_내림차순이다() {
        // 룰 1 대상(실패 적음)
        Routine dropTarget = weekly(1L, "룰1", "MON", "WED");
        List<RoutineLog> logs = new ArrayList<>();
        for (int week = 0; week < 3; week++) {
            logs.add(log(dropTarget, dateOf(week, DayOfWeek.MONDAY), RoutineLogStatus.COMPLETED));
            logs.add(log(dropTarget, dateOf(week, DayOfWeek.WEDNESDAY), RoutineLogStatus.FAILED));
        }
        // 룰 2 대상(실패 많음)
        Routine reduceTarget = daily(2L, "룰2");
        logs.add(log(reduceTarget, dateOf(0, DayOfWeek.MONDAY), RoutineLogStatus.COMPLETED));
        logs.add(log(reduceTarget, dateOf(0, DayOfWeek.TUESDAY), RoutineLogStatus.COMPLETED));
        for (int week = 1; week < 3; week++) {
            for (DayOfWeek day : DayOfWeek.values()) {
                logs.add(log(reduceTarget, dateOf(week, day),
                        day == DayOfWeek.MONDAY ? RoutineLogStatus.COMPLETED : RoutineLogStatus.FAILED));
            }
        }

        List<Proposal> proposals = evaluator.evaluate(List.of(reduceTarget, dropTarget), byLineage(logs), WEEKS);

        assertThat(proposals).hasSize(2);
        assertThat(proposals.get(0).originRoutineId()).isEqualTo(1L);
        assertThat(proposals.get(1).originRoutineId()).isEqualTo(2L);
    }

    // ---------- fixtures ----------

    private static Routine weekly(long id, String title, String... dayTokens) {
        StringBuilder days = new StringBuilder("{\"daysOfWeek\":[");
        for (int i = 0; i < dayTokens.length; i++) {
            days.append('"').append(dayTokens[i]).append('"');
            if (i < dayTokens.length - 1) {
                days.append(',');
            }
        }
        days.append("]}");
        return routine(id, title, "WEEKLY", days.toString());
    }

    private static Routine daily(long id, String title) {
        return routine(id, title, "DAILY", null);
    }

    private static Routine routine(long id, String title, String repeatType, String repeatDaysJson) {
        User user = User.signUp();
        ReflectionTestUtils.setField(user, "id", 100L);
        Routine routine = Routine.create(user, null, title, AuthType.CHECK, repeatType, repeatDaysJson,
                null, null, null);
        ReflectionTestUtils.setField(routine, "id", id);
        ReflectionTestUtils.setField(routine, "originRoutineId", id);
        return routine;
    }

    private static RoutineLog log(Routine routine, LocalDate date, RoutineLogStatus status) {
        return status == RoutineLogStatus.COMPLETED
                ? RoutineLog.complete(routine, date, Instant.now(), CurrencyType.COIN, 0)
                : RoutineLog.fail(routine, date);
    }

    private static Map<Long, List<RoutineLog>> byLineage(List<RoutineLog> logs) {
        Map<Long, List<RoutineLog>> grouped = new java.util.HashMap<>();
        for (RoutineLog routineLog : logs) {
            grouped.computeIfAbsent(RecommendationRuleEvaluator.lineageKey(routineLog.getRoutine()),
                    key -> new ArrayList<>()).add(routineLog);
        }
        return grouped;
    }

    // week: 0(창의 첫 주)~2(대상 주), 요일은 일~토 주 안에서의 날짜
    private static LocalDate dateOf(int week, DayOfWeek day) {
        LocalDate weekStart = WEEKS.get(week).start();
        int offset = day == DayOfWeek.SUNDAY ? 0 : day.getValue();
        return weekStart.plusDays(offset);
    }
}
