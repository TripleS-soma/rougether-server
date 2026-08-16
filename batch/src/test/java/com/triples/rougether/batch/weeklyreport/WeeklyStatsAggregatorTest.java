package com.triples.rougether.batch.weeklyreport;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.report.WeeklyReportStats;
import com.triples.rougether.domain.report.WeeklyReportStats.RoutineStat;
import com.triples.rougether.domain.report.WeeklyReportStats.WeekdayStat;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.Streak;
import com.triples.rougether.domain.shared.CurrencyType;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class WeeklyStatsAggregatorTest {

    // 2026-08-09(일) ~ 2026-08-15(토)
    private static final LocalDate SUN = LocalDate.of(2026, 8, 9);
    private final WeeklyStatsAggregator aggregator = new WeeklyStatsAggregator();
    private final User user = User.signUp();

    @Test
    void 완료_실패_수와_완료율을_계산한다() {
        Routine run = routine(1L, null, "달리기", null);
        List<RoutineLog> logs = List.of(
                completed(run, SUN), completed(run, SUN.plusDays(1)), failed(run, SUN.plusDays(2)));

        WeeklyReportStats stats = aggregator.aggregate(logs, Optional.empty());

        assertThat(stats.scheduledCount()).isEqualTo(3);
        assertThat(stats.completedCount()).isEqualTo(2);
        assertThat(stats.failedCount()).isEqualTo(1);
        assertThat(stats.completionRate()).isEqualTo(0.67);
    }

    @Test
    void 요일별_집계는_일요일부터_7개_고정이다() {
        Routine run = routine(1L, null, "달리기", null);
        List<RoutineLog> logs = List.of(completed(run, SUN), failed(run, SUN.plusDays(6)));

        WeeklyReportStats stats = aggregator.aggregate(logs, Optional.empty());

        assertThat(stats.byWeekday()).hasSize(7);
        assertThat(stats.byWeekday()).extracting(WeekdayStat::dayOfWeek).containsExactly(
                DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY);
        assertThat(stats.byWeekday().getFirst().completed()).isEqualTo(1);
        assertThat(stats.byWeekday().getLast().failed()).isEqualTo(1);
        assertThat(stats.byWeekday().get(3).completed() + stats.byWeekday().get(3).failed()).isZero();
    }

    @Test
    void 버전_분기된_루틴은_계보로_병합하고_최신_버전_제목을_쓴다() {
        Category category = Category.create(user, "건강", null, null, 0, PrivacyScope.PRIVATE);
        Routine oldVersion = routine(10L, 10L, "물 마시기", category);
        Routine newVersion = routine(11L, 10L, "물 2L 마시기", category);
        List<RoutineLog> logs = List.of(
                completed(oldVersion, SUN), failed(oldVersion, SUN.plusDays(1)), completed(newVersion, SUN.plusDays(2)));

        WeeklyReportStats stats = aggregator.aggregate(logs, Optional.empty());

        assertThat(stats.byRoutine()).hasSize(1);
        RoutineStat stat = stats.byRoutine().getFirst();
        assertThat(stat.lineageId()).isEqualTo(10L);
        assertThat(stat.title()).isEqualTo("물 2L 마시기");
        assertThat(stat.categoryName()).isEqualTo("건강");
        assertThat(stat.completed()).isEqualTo(2);
        assertThat(stat.failed()).isEqualTo(1);
    }

    @Test
    void 삭제된_카테고리는_이름을_내려주지_않는다() {
        Category category = Category.create(user, "삭제됨", null, null, 0, PrivacyScope.PRIVATE);
        category.softDelete(Instant.now());
        Routine run = routine(1L, null, "달리기", category);

        WeeklyReportStats stats = aggregator.aggregate(List.of(completed(run, SUN)), Optional.empty());

        assertThat(stats.byRoutine().getFirst().categoryName()).isNull();
    }

    @Test
    void 스트릭_스냅샷은_없으면_0이고_있으면_현재_최장을_담는다() {
        Routine run = routine(1L, null, "달리기", null);
        List<RoutineLog> logs = List.of(completed(run, SUN));

        WeeklyReportStats withoutStreak = aggregator.aggregate(logs, Optional.empty());
        assertThat(withoutStreak.streak().currentCount()).isZero();
        assertThat(withoutStreak.streak().longestCount()).isZero();

        Streak streak = Streak.start(user, SUN);
        streak.applySuccess(SUN.plusDays(1));
        streak.applySuccess(SUN.plusDays(2));
        WeeklyReportStats withStreak = aggregator.aggregate(logs, Optional.of(streak));
        assertThat(withStreak.streak().currentCount()).isEqualTo(3);
        assertThat(withStreak.streak().longestCount()).isEqualTo(3);
    }

    @Test
    void 로그가_없으면_모두_0이고_완료율도_0이다() {
        WeeklyReportStats stats = aggregator.aggregate(List.of(), Optional.empty());

        assertThat(stats.scheduledCount()).isZero();
        assertThat(stats.completionRate()).isZero();
        assertThat(stats.byRoutine()).isEmpty();
        assertThat(stats.byWeekday()).hasSize(7);
    }

    @Test
    void 완료율은_소수_둘째_자리에서_반올림한다() {
        assertThat(WeeklyStatsAggregator.completionRate(1, 3)).isEqualTo(0.33);
        assertThat(WeeklyStatsAggregator.completionRate(2, 3)).isEqualTo(0.67);
        assertThat(WeeklyStatsAggregator.completionRate(5, 8)).isEqualTo(0.63);
        assertThat(WeeklyStatsAggregator.completionRate(3, 3)).isEqualTo(1.0);
    }

    private Routine routine(Long id, Long originId, String title, Category category) {
        Routine routine = Routine.create(user, category, title, AuthType.CHECK, "DAILY", null, null, null, null);
        ReflectionTestUtils.setField(routine, "id", id);
        if (originId != null) {
            ReflectionTestUtils.setField(routine, "originRoutineId", originId);
        }
        return routine;
    }

    private static RoutineLog completed(Routine routine, LocalDate date) {
        return RoutineLog.complete(routine, date, Instant.now(), CurrencyType.COIN, 10);
    }

    private static RoutineLog failed(Routine routine, LocalDate date) {
        return RoutineLog.fail(routine, date);
    }
}
