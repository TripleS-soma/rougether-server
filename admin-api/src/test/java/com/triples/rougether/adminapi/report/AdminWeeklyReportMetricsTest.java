package com.triples.rougether.adminapi.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.adminapi.report.dto.AdminWeeklyReportMetricsResponse;
import com.triples.rougether.adminapi.report.dto.AdminWeeklyReportMetricsResponse.WeekMetric;
import com.triples.rougether.adminapi.report.dto.AdminWeeklyReportMetricsResponse.WeekState;
import com.triples.rougether.adminapi.report.service.AdminWeeklyReportMetricsService;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.report.WeeklyReportPolicy;
import com.triples.rougether.domain.report.entity.WeeklyReport;
import com.triples.rougether.domain.report.repository.WeeklyReportRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

// 기준 시각을 미래(2030년)로 고정한다. 테스트가 만드는 log 의 createdAt(실제 현재)이 어느 주 cutoff 보다도 앞서
// "배치 시점에 이미 있던 기록"으로 세어지고, 실행 요일에 따라 주차·pending 판정이 흔들리지 않는다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminWeeklyReportMetricsTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 2030-08-20 은 화요일. 최근 완료 주 = 2030-08-11(일) ~ 08-17(토), 첫 배치 = 08-18 00:30 KST.
    private static final Instant NOW = LocalDateTime.of(2030, 8, 20, 12, 0).atZone(KST).toInstant();
    private static final LocalDate WEEK_START = LocalDate.of(2030, 8, 11);
    private static final LocalDate WEEK_END = LocalDate.of(2030, 8, 17);
    private static final Instant BATCH_RAN_AT = LocalDateTime.of(2030, 8, 18, 0, 35).atZone(KST).toInstant();

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedKstClock() {
            return Clock.fixed(NOW, KST);
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AdminWeeklyReportMetricsService metricsService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    RoutineRepository routineRepository;

    @Autowired
    RoutineLogRepository routineLogRepository;

    @Autowired
    WeeklyReportRepository weeklyReportRepository;

    @Test
    @WithMockUser(roles = "ADMIN")
    void 최근_완료_주의_대상_생성_FALLBACK_누락을_집계한다() throws Exception {
        User generated = userWithLog("metrics-generated@rougether.dev", WEEK_START);
        User fallback = userWithLog("metrics-fallback@rougether.dev", WEEK_START.plusDays(2));
        userWithLog("metrics-missing@rougether.dev", WEEK_END); // 대상이지만 회고가 없는 사용자 = 누락
        // 배치 이전에 탈퇴한 회원의 log 는 대상에서 제외돼야 한다(batch 와 같은 조건).
        User withdrawn = userWithLog("metrics-withdrawn@rougether.dev", WEEK_START.plusDays(1));
        withdrawn.softDelete(BATCH_RAN_AT.minusSeconds(3600));
        withdrawn.anonymize();
        // 지난주 log 만 있는 사용자는 이번 주 대상이 아니다.
        userWithLog("metrics-previous-week@rougether.dev", WEEK_START.minusDays(1));

        weeklyReportRepository.save(WeeklyReport.generated(generated, WEEK_START, WEEK_END,
                "gpt-test", "{}", "생성 회고", "{}", BATCH_RAN_AT.minusSeconds(60)));
        weeklyReportRepository.save(WeeklyReport.fallback(fallback, WEEK_START, WEEK_END,
                "{}", "폴백 회고", "{}", BATCH_RAN_AT));

        mockMvc.perform(get("/admin/weekly-reports/metrics").param("weeks", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weeks.length()").value(2))
                .andExpect(jsonPath("$.weeks[0].weekStartDate").value(WEEK_START.toString()))
                .andExpect(jsonPath("$.weeks[0].weekEndDate").value(WEEK_END.toString()))
                .andExpect(jsonPath("$.weeks[0].state").value("PARTIAL"))
                .andExpect(jsonPath("$.weeks[0].eligibleUsers").value(3))
                .andExpect(jsonPath("$.weeks[0].generatedCount").value(1))
                .andExpect(jsonPath("$.weeks[0].fallbackCount").value(1))
                .andExpect(jsonPath("$.weeks[0].missingCount").value(1))
                .andExpect(jsonPath("$.weeks[0].coverageRate").value(closeTo(66.7)))
                .andExpect(jsonPath("$.weeks[0].fallbackRate").value(50.0))
                .andExpect(jsonPath("$.weeks[0].models[0]").value("gpt-test"))
                .andExpect(jsonPath("$.weeks[0].lastGeneratedAt").value(BATCH_RAN_AT.toString()))
                .andExpect(jsonPath("$.weeks[1].weekStartDate").value(WEEK_START.minusWeeks(1).toString()))
                .andExpect(jsonPath("$.weeks[1].state").value("NOT_GENERATED"))
                .andExpect(jsonPath("$.weeks[1].eligibleUsers").value(1))
                .andExpect(jsonPath("$.weeks[1].generatedCount").value(0))
                .andExpect(jsonPath("$.weeks[1].missingCount").value(1));
    }

    @Test
    void 배치_이후에_삭제된_루틴과_탈퇴한_회원은_그_주_대상에_그대로_남는다() {
        // 배치가 돈 뒤 루틴을 지우거나 탈퇴해도, 배치 시점에는 대상이었으므로 분모에서 빠지면 안 된다(누락 은폐 방지).
        User laterDeletedRoutine = userWithLog("metrics-later-deleted-routine@rougether.dev", WEEK_START);
        routineRepository.findAll().stream()
                .filter(routine -> routine.getUser().getId().equals(laterDeletedRoutine.getId()))
                .forEach(routine -> routine.softDelete(BATCH_RAN_AT.plusSeconds(3600)));
        User laterWithdrawn = userWithLog("metrics-later-withdrawn@rougether.dev", WEEK_START.plusDays(3));
        laterWithdrawn.softDelete(BATCH_RAN_AT.plusSeconds(7200));
        laterWithdrawn.anonymize();
        weeklyReportRepository.save(WeeklyReport.generated(laterDeletedRoutine, WEEK_START, WEEK_END,
                "gpt-test", "{}", "회고", "{}", BATCH_RAN_AT));
        weeklyReportRepository.save(WeeklyReport.generated(laterWithdrawn, WEEK_START, WEEK_END,
                "gpt-test", "{}", "회고", "{}", BATCH_RAN_AT));

        WeekMetric latest = metricsService.getMetrics(1).weeks().get(0);

        assertThat(latest.eligibleUsers()).isEqualTo(2);
        assertThat(latest.generatedCount()).isEqualTo(2);
        assertThat(latest.missingCount()).isZero();
        assertThat(latest.state()).isEqualTo(WeekState.COMPLETE);
    }

    @Test
    void 생성_건수가_대상을_넘으면_기준_변동으로_표시하고_커버리지는_100으로_묶는다() {
        // 배치 이후 완료 취소로 log 자체가 지워진 경우: 회고는 남고 대상은 0.
        User user = userRepository.save(User.signUp("metrics-shifted@rougether.dev"));
        weeklyReportRepository.save(WeeklyReport.generated(user, WEEK_START, WEEK_END,
                "gpt-test", "{}", "회고", "{}", BATCH_RAN_AT));

        WeekMetric latest = metricsService.getMetrics(1).weeks().get(0);

        assertThat(latest.eligibleUsers()).isZero();
        assertThat(latest.generatedCount()).isEqualTo(1);
        assertThat(latest.state()).isEqualTo(WeekState.BASELINE_SHIFTED);
        assertThat(latest.coverageRate()).isEqualTo(0.0); // 대상 0 이면 비율 정의 불가 → 0
        assertThat(latest.missingCount()).isZero();
    }

    @Test
    void 첫_배치_이전이면_PENDING_이고_대상은_지금까지의_기록으로_센다() {
        // 일요일 00:10 KST — 08-11~17 주가 방금 끝났고 첫 배치(00:30) 전.
        Instant sundayEarly = LocalDateTime.of(2030, 8, 18, 0, 10).atZone(KST).toInstant();
        AdminWeeklyReportMetricsService pendingService = new AdminWeeklyReportMetricsService(
                weeklyReportRepository, routineLogRepository, Clock.fixed(sundayEarly, KST));
        userWithLog("metrics-pending@rougether.dev", WEEK_END);

        WeekMetric latest = pendingService.getMetrics(1).weeks().get(0);

        assertThat(latest.weekStartDate()).isEqualTo(WEEK_START);
        assertThat(latest.state()).isEqualTo(WeekState.PENDING);
        assertThat(latest.eligibleUsers()).isEqualTo(1);
        assertThat(latest.generatedCount()).isZero();
    }

    @Test
    void 조회_주_수는_1_이상_26_이하로_묶인다() {
        AdminWeeklyReportMetricsResponse tooMany = metricsService.getMetrics(999);
        AdminWeeklyReportMetricsResponse tooFew = metricsService.getMetrics(0);

        assertThat(tooMany.weeks()).hasSize(AdminWeeklyReportMetricsService.MAX_WEEKS);
        assertThat(tooFew.weeks()).hasSize(1);
        WeekMetric latest = tooFew.weeks().get(0);
        assertThat(latest.weekStartDate()).isEqualTo(WEEK_START);
        assertThat(latest.weekStartDate().getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
        assertThat(latest.weekEndDate()).isEqualTo(WEEK_END);
        assertThat(tooFew.generatedAt()).isEqualTo(NOW);
    }

    @Test
    void 대상도_회고도_없는_주는_NO_TARGET_이고_비율은_0이다() {
        AdminWeeklyReportMetricsResponse response = metricsService.getMetrics(AdminWeeklyReportMetricsService.MAX_WEEKS);
        WeekMetric oldest = response.weeks().get(response.weeks().size() - 1);

        assertThat(oldest.state()).isEqualTo(WeekState.NO_TARGET);
        assertThat(oldest.eligibleUsers()).isZero();
        assertThat(oldest.coverageRate()).isZero();
        assertThat(oldest.fallbackRate()).isZero();
        assertThat(oldest.models()).isEmpty();
        assertThat(oldest.lastGeneratedAt()).isNull();
    }

    @Test
    void 주_경계는_batch_와_같은_WeeklyReportPolicy_를_쓴다() {
        assertThat(WeeklyReportPolicy.latestCompletedWeekStart(LocalDate.of(2026, 8, 16)))
                .isEqualTo(LocalDate.of(2026, 8, 9)); // 일요일 당일이면 지난주
        assertThat(WeeklyReportPolicy.latestCompletedWeekStart(LocalDate.of(2026, 8, 18)))
                .isEqualTo(LocalDate.of(2026, 8, 9)); // 화요일이면 직전 일~토 주
        assertThat(WeeklyReportPolicy.latestCompletedWeekStart(LocalDate.of(2026, 8, 22)))
                .isEqualTo(LocalDate.of(2026, 8, 9)); // 토요일도 아직 이번 주 진행 중
        assertThat(AdminWeeklyReportMetricsService.firstTriggerAt(WEEK_END))
                .isEqualTo(LocalDateTime.of(2030, 8, 18, 0, 30).atZone(KST).toInstant());
    }

    @Test
    void 미인증이면_관측_API_에_접근할_수_없다() throws Exception {
        mockMvc.perform(get("/admin/weekly-reports/metrics"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(username = "observer", roles = "ADMIN")
    void AI_회고_관측_페이지를_렌더링한다() throws Exception {
        mockMvc.perform(get("/weekly-reports"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AI 주간 회고 생성 관측")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("FALLBACK")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/weekly-reports/metrics")));
    }

    private User userWithLog(String email, LocalDate routineDate) {
        User user = userRepository.save(User.signUp(email));
        Routine routine = routineRepository.save(Routine.create(
                user, null, "회고 관측 루틴", AuthType.CHECK, "DAILY", "[]", null,
                routineDate.minusDays(30), null));
        routineLogRepository.save(RoutineLog.complete(
                routine, routineDate, routineDate.atStartOfDay(KST).toInstant(), CurrencyType.COIN, 10));
        return user;
    }

    private static org.hamcrest.Matcher<Double> closeTo(double expected) {
        return org.hamcrest.Matchers.closeTo(expected, 0.1);
    }
}
