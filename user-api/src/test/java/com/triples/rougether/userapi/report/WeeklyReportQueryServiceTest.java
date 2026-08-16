package com.triples.rougether.userapi.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.report.WeeklyReportSections;
import com.triples.rougether.domain.report.WeeklyReportStats;
import com.triples.rougether.domain.report.WeeklyReportStats.RoutineStat;
import com.triples.rougether.domain.report.WeeklyReportStats.StreakSnapshot;
import com.triples.rougether.domain.report.WeeklyReportStats.WeekdayStat;
import com.triples.rougether.domain.report.entity.WeeklyReport;
import com.triples.rougether.domain.report.entity.WeeklyReportStatus;
import com.triples.rougether.domain.report.repository.WeeklyReportRepository;
import com.triples.rougether.userapi.report.dto.WeeklyReportDetailResponse;
import com.triples.rougether.userapi.report.dto.WeeklyReportListResponse;
import com.triples.rougether.userapi.report.error.WeeklyReportErrorCode;
import com.triples.rougether.userapi.report.service.WeeklyReportQueryService;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

// 배치(#286)가 저장한 weekly_reports 를 실제 DB 에 넣고 조회 계약(정렬·소유권·FALLBACK 섹션)을 검증한다.
@SpringBootTest
@Transactional
class WeeklyReportQueryServiceTest {

    private static final LocalDate WEEK1 = LocalDate.of(2026, 8, 2);
    private static final LocalDate WEEK2 = LocalDate.of(2026, 8, 9);
    private static final Instant GENERATED_AT = Instant.parse("2026-08-16T00:30:00Z");

    @Autowired private WeeklyReportQueryService weeklyReportQueryService;
    @Autowired private WeeklyReportRepository weeklyReportRepository;
    @Autowired private UserRepository userRepository;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private User me;
    private User other;

    @BeforeEach
    void setUp() {
        me = userRepository.save(User.signUp("report-me@rougether.dev"));
        other = userRepository.save(User.signUp("report-other@rougether.dev"));
    }

    @Test
    void 내_회고를_최신_주부터_핵심_통계와_함께_돌려준다() {
        saveGenerated(me, WEEK1, stats(5, 3), "첫 주 회고");
        saveGenerated(me, WEEK2, stats(4, 4), "둘째 주 회고");
        saveGenerated(other, WEEK2, stats(1, 0), "남의 회고");

        WeeklyReportListResponse response = weeklyReportQueryService.getMyReports(me.getId());

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).weekStartDate()).isEqualTo(WEEK2);
        assertThat(response.items().get(0).weekEndDate()).isEqualTo(WEEK2.plusDays(6));
        assertThat(response.items().get(0).status()).isEqualTo(WeeklyReportStatus.GENERATED);
        assertThat(response.items().get(0).completionRate()).isEqualTo(1.0);
        assertThat(response.items().get(0).completedCount()).isEqualTo(4);
        assertThat(response.items().get(0).scheduledCount()).isEqualTo(4);
        assertThat(response.items().get(0).summary()).isEqualTo("둘째 주 회고");
        assertThat(response.items().get(0).generatedAt()).isEqualTo(GENERATED_AT);
        assertThat(response.items().get(1).weekStartDate()).isEqualTo(WEEK1);
        assertThat(response.items().get(1).completionRate()).isEqualTo(0.6);
    }

    @Test
    void 회고가_없으면_빈_목록이다() {
        assertThat(weeklyReportQueryService.getMyReports(me.getId()).items()).isEmpty();
    }

    @Test
    void 상세는_통계와_섹션까지_풀어서_돌려준다() {
        WeeklyReport saved = saveGenerated(me, WEEK2, stats(5, 3), "상세 회고");

        WeeklyReportDetailResponse detail = weeklyReportQueryService.getMyReport(me.getId(), saved.getId());

        assertThat(detail.reportId()).isEqualTo(saved.getId());
        assertThat(detail.status()).isEqualTo(WeeklyReportStatus.GENERATED);
        assertThat(detail.summary()).isEqualTo("상세 회고");
        assertThat(detail.stats().scheduledCount()).isEqualTo(5);
        assertThat(detail.stats().completedCount()).isEqualTo(3);
        assertThat(detail.stats().failedCount()).isEqualTo(2);
        assertThat(detail.stats().byWeekday()).hasSize(7);
        assertThat(detail.stats().byWeekday().get(0).dayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
        assertThat(detail.stats().byRoutine()).hasSize(1);
        assertThat(detail.stats().byRoutine().get(0).title()).isEqualTo("달리기");
        assertThat(detail.stats().byRoutine().get(0).categoryName()).isEqualTo("건강");
        assertThat(detail.stats().streak().currentCount()).isEqualTo(3);
        assertThat(detail.stats().streak().longestCount()).isEqualTo(9);
        assertThat(detail.highlights()).containsExactly("일요일 달리기");
        assertThat(detail.failurePatterns()).containsExactly("화요일 실패");
        assertThat(detail.suggestions()).containsExactly("알람 켜기");
    }

    @Test
    void FALLBACK_회고는_섹션이_빈_배열이다() {
        WeeklyReport fallback = weeklyReportRepository.save(WeeklyReport.fallback(me, WEEK2, WEEK2.plusDays(6),
                json(stats(3, 2)), "이번 주 루틴 3회 중 2회를 완료했어요.", json(WeeklyReportSections.empty()),
                GENERATED_AT));

        WeeklyReportDetailResponse detail = weeklyReportQueryService.getMyReport(me.getId(), fallback.getId());

        assertThat(detail.status()).isEqualTo(WeeklyReportStatus.FALLBACK);
        assertThat(detail.summary()).isEqualTo("이번 주 루틴 3회 중 2회를 완료했어요.");
        assertThat(detail.highlights()).isEmpty();
        assertThat(detail.failurePatterns()).isEmpty();
        assertThat(detail.suggestions()).isEmpty();
        assertThat(detail.stats().completedCount()).isEqualTo(2);
    }

    @Test
    void 타인의_회고_id는_존재해도_404() {
        WeeklyReport theirs = saveGenerated(other, WEEK2, stats(1, 1), "남의 회고");

        assertThatThrownBy(() -> weeklyReportQueryService.getMyReport(me.getId(), theirs.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(WeeklyReportErrorCode.WEEKLY_REPORT_NOT_FOUND);
    }

    @Test
    void 없는_id는_404() {
        assertThatThrownBy(() -> weeklyReportQueryService.getMyReport(me.getId(), 999_999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(WeeklyReportErrorCode.WEEKLY_REPORT_NOT_FOUND);
    }

    private WeeklyReport saveGenerated(User user, LocalDate weekStart, WeeklyReportStats stats, String summary) {
        WeeklyReportSections sections = new WeeklyReportSections(
                List.of("일요일 달리기"), List.of("화요일 실패"), List.of("알람 켜기"));
        return weeklyReportRepository.save(WeeklyReport.generated(user, weekStart, weekStart.plusDays(6),
                "test-model", json(stats), summary, json(sections), GENERATED_AT));
    }

    // 배치의 WeeklyStatsAggregator 가 만드는 것과 같은 스키마(요일 7개 고정, 계보별 루틴, 스트릭)
    private static WeeklyReportStats stats(int scheduled, int completed) {
        int failed = scheduled - completed;
        List<WeekdayStat> weekdays = List.of(
                new WeekdayStat(DayOfWeek.SUNDAY, completed, 0),
                new WeekdayStat(DayOfWeek.MONDAY, 0, 0),
                new WeekdayStat(DayOfWeek.TUESDAY, 0, failed),
                new WeekdayStat(DayOfWeek.WEDNESDAY, 0, 0),
                new WeekdayStat(DayOfWeek.THURSDAY, 0, 0),
                new WeekdayStat(DayOfWeek.FRIDAY, 0, 0),
                new WeekdayStat(DayOfWeek.SATURDAY, 0, 0));
        double rate = scheduled == 0 ? 0.0 : Math.round(completed * 100.0 / scheduled) / 100.0;
        return new WeeklyReportStats(scheduled, completed, failed, rate, weekdays,
                List.of(new RoutineStat(1L, "달리기", "건강", completed, failed)), new StreakSnapshot(3, 9));
    }

    private String json(Object value) {
        return jsonMapper.writeValueAsString(value);
    }
}
