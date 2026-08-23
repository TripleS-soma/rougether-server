package com.triples.rougether.adminapi.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.adminapi.recommendation.dto.AdminRecommendationMetricsResponse;
import com.triples.rougether.adminapi.recommendation.dto.AdminRecommendationMetricsResponse.WeekMetric;
import com.triples.rougether.adminapi.recommendation.service.AdminRecommendationMetricsService;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.recommendation.entity.RecommendationType;
import com.triples.rougether.domain.recommendation.entity.RoutineRecommendation;
import com.triples.rougether.domain.recommendation.repository.RoutineRecommendationRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import java.sql.Timestamp;
import java.time.Clock;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

// 추천 퍼널 관측(#332). 기준 시각을 미래(2030년)로 고정하고, auditing 이 실제 현재로 채우는 created_at 은
// native update 로 관측 주에 맞춰 옮긴다(생성 주 버킷이 실행 시점에 흔들리지 않게).
// 2030-08-20 은 화요일 - 진행 중 주 = 08-18(일)~08-24(토), 그 전 주 = 08-11, 그 전전 주 = 08-04.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminRecommendationMetricsTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = LocalDateTime.of(2030, 8, 20, 12, 0).atZone(KST).toInstant();
    private static final LocalDate CURRENT_WEEK = LocalDate.of(2030, 8, 18);
    private static final LocalDate LAST_WEEK = LocalDate.of(2030, 8, 11);
    private static final LocalDate BATCH_WEEK = LocalDate.of(2030, 8, 4);
    private static final String PROPOSAL = "{\"repeatType\":\"WEEKLY\",\"daysOfWeek\":[\"MON\"]}";

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
    AdminRecommendationMetricsService metricsService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    RoutineRepository routineRepository;

    @Autowired
    RoutineLogRepository routineLogRepository;

    @Autowired
    RoutineRecommendationRepository routineRecommendationRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void 생성_주_버킷으로_수락_무시_만료_대기와_수락_효과를_집계한다() {
        // BATCH_WEEK(08-04) 생성분 4건: 수락(효과 측정) + 수락(측정 불가) + 무시 + 만료
        User userA = userRepository.save(User.signUp("rec-metrics-a@rougether.dev"));
        Routine measuredRoutine = saveRoutine(userA, "물 마시기");
        // 수락 08-05(월, 수락 주=08-04) → 직전 주 [07-28..08-03] 완료 1/4 = 25%, 다음 주 [08-11..08-17] 완료 3/4 = 75%
        saveLog(measuredRoutine, LocalDate.of(2030, 7, 28), true);
        saveLog(measuredRoutine, LocalDate.of(2030, 7, 29), false);
        saveLog(measuredRoutine, LocalDate.of(2030, 7, 31), false);
        saveLog(measuredRoutine, LocalDate.of(2030, 8, 2), false);
        saveLog(measuredRoutine, LocalDate.of(2030, 8, 11), true);
        saveLog(measuredRoutine, LocalDate.of(2030, 8, 13), true);
        saveLog(measuredRoutine, LocalDate.of(2030, 8, 15), true);
        saveLog(measuredRoutine, LocalDate.of(2030, 8, 16), false);
        RoutineRecommendation acceptedMeasured = saveRecommendation(userA, measuredRoutine,
                BATCH_WEEK, instantKst(2030, 8, 11, 0));
        acceptedMeasured.accept(instantKst(2030, 8, 5, 9), measuredRoutine.getId());

        Routine dismissedRoutine = saveRoutine(userA, "스트레칭");
        RoutineRecommendation dismissed = saveRecommendation(userA, dismissedRoutine,
                BATCH_WEEK, instantKst(2030, 8, 11, 0));
        dismissed.dismiss(instantKst(2030, 8, 6, 9));

        User userB = userRepository.save(User.signUp("rec-metrics-b@rougether.dev"));
        Routine noLogRoutine = saveRoutine(userB, "독서");
        // 수락했지만 전후 주에 log 가 없어 측정 불가
        RoutineRecommendation acceptedUnmeasurable = saveRecommendation(userB, noLogRoutine,
                BATCH_WEEK, instantKst(2030, 8, 11, 0));
        acceptedUnmeasurable.accept(instantKst(2030, 8, 5, 9), noLogRoutine.getId());

        Routine expiredRoutine = saveRoutine(userB, "달리기");
        // ACTIVE 인 채 expires_at(08-11) 경과 = lazy 만료
        saveRecommendation(userB, expiredRoutine, BATCH_WEEK, instantKst(2030, 8, 11, 0));

        // LAST_WEEK(08-11) 생성 1건: 수락했지만 다음 주(08-18~24)가 안 끝나 효과는 측정 대기
        User userC = userRepository.save(User.signUp("rec-metrics-c@rougether.dev"));
        Routine pendingEffectRoutine = saveRoutine(userC, "명상");
        RoutineRecommendation acceptedPendingEffect = saveRecommendation(userC, pendingEffectRoutine,
                LAST_WEEK, instantKst(2030, 8, 18, 0));
        acceptedPendingEffect.accept(instantKst(2030, 8, 12, 9), pendingEffectRoutine.getId());

        // CURRENT_WEEK(08-18) 생성 1건: 응답 기한이 남은 대기
        Routine waitingRoutine = saveRoutine(userC, "일기");
        saveRecommendation(userC, waitingRoutine, CURRENT_WEEK, instantKst(2030, 8, 25, 0));

        AdminRecommendationMetricsResponse response = metricsService.getMetrics(3);

        assertThat(response.generatedAt()).isEqualTo(NOW);
        WeekMetric current = response.weeks().get(0);
        assertThat(current.weekStartDate()).isEqualTo(CURRENT_WEEK);
        assertThat(current.weekEndDate()).isEqualTo(CURRENT_WEEK.plusDays(6));
        assertThat(current.inProgress()).isTrue();
        assertThat(current.createdCount()).isEqualTo(1);
        assertThat(current.pendingCount()).isEqualTo(1);
        assertThat(current.expiredCount()).isZero();

        WeekMetric last = response.weeks().get(1);
        assertThat(last.weekStartDate()).isEqualTo(LAST_WEEK);
        assertThat(last.inProgress()).isFalse();
        assertThat(last.createdCount()).isEqualTo(1);
        assertThat(last.acceptedCount()).isEqualTo(1);
        assertThat(last.effectPendingCount()).isEqualTo(1);
        assertThat(last.effectMeasuredCount()).isZero();
        assertThat(last.avgCompletionDeltaPp()).isNull();

        WeekMetric batchWeek = response.weeks().get(2);
        assertThat(batchWeek.weekStartDate()).isEqualTo(BATCH_WEEK);
        assertThat(batchWeek.createdCount()).isEqualTo(4);
        assertThat(batchWeek.acceptedCount()).isEqualTo(2);
        assertThat(batchWeek.dismissedCount()).isEqualTo(1);
        assertThat(batchWeek.expiredCount()).isEqualTo(1);
        assertThat(batchWeek.pendingCount()).isZero();
        assertThat(batchWeek.acceptedRate()).isEqualTo(50.0);
        assertThat(batchWeek.respondedRate()).isEqualTo(75.0);
        assertThat(batchWeek.effectMeasuredCount()).isEqualTo(1);
        assertThat(batchWeek.effectUnmeasurableCount()).isEqualTo(1);
        assertThat(batchWeek.effectPendingCount()).isZero();
        assertThat(batchWeek.avgCompletionDeltaPp()).isEqualTo(50.0);
    }

    @Test
    void 기한이_남아도_루틴_삭제나_선행_수정으로_무효면_만료로_센다() {
        // 사용자 목록(lazy 필터)에서 이미 빠진 ACTIVE 건은 대기가 아니라 만료와 같은 무반응 종결(#333 리뷰)
        User user = userRepository.save(User.signUp("rec-metrics-invalid@rougether.dev"));
        Routine deletedRoutine = saveLineageRoot(user, "삭제된 루틴");
        saveRecommendation(user, deletedRoutine, CURRENT_WEEK, instantKst(2030, 8, 25, 0));
        deletedRoutine.softDelete(instantKst(2030, 8, 19, 0));

        Routine staleRoot = saveLineageRoot(user, "선수정된 루틴");
        saveRecommendation(user, staleRoot, CURRENT_WEEK, instantKst(2030, 8, 25, 0));
        // 사용자가 추천과 무관하게 스케줄을 먼저 수정 → 버전 분기, 생성 시점 대상 버전은 닫힘(stale)
        routineRepository.save(staleRoot.copyAsNewVersion(null, null, null, null, null, null, null, null));
        staleRoot.softDelete(instantKst(2030, 8, 19, 0));

        Routine actionableRoutine = saveLineageRoot(user, "유효한 루틴");
        saveRecommendation(user, actionableRoutine, CURRENT_WEEK, instantKst(2030, 8, 25, 0));

        WeekMetric current = metricsService.getMetrics(1).weeks().get(0);

        assertThat(current.createdCount()).isEqualTo(3);
        assertThat(current.pendingCount()).isEqualTo(1);
        assertThat(current.expiredCount()).isEqualTo(2);
    }

    @Test
    void 다음_주_완료율은_수락_적용_버전의_기록만_귀속한다() {
        // 수락 뒤 사용자가 또 수정해 재분기한 버전의 log 는 이 추천의 효과에서 제외(#333 리뷰 — applied_routine_id 조인 키)
        User user = userRepository.save(User.signUp("rec-metrics-applied@rougether.dev"));
        Routine origin = saveLineageRoot(user, "원본 버전");
        saveLog(origin, LocalDate.of(2030, 7, 28), true);
        saveLog(origin, LocalDate.of(2030, 7, 30), false); // 직전 주 완료율 1/2 = 50%
        RoutineRecommendation accepted = saveRecommendation(user, origin, BATCH_WEEK, instantKst(2030, 8, 11, 0));

        Routine applied = routineRepository.save(
                origin.copyAsNewVersion(null, null, null, null, null, null, null, null));
        origin.softDelete(instantKst(2030, 8, 5, 9));
        accepted.accept(instantKst(2030, 8, 5, 9), applied.getId());
        saveLog(applied, LocalDate.of(2030, 8, 11), true);
        saveLog(applied, LocalDate.of(2030, 8, 12), true); // 적용 버전 다음 주 완료율 2/2 = 100%

        Routine refork = routineRepository.save(
                applied.copyAsNewVersion(null, null, null, null, null, null, null, null));
        applied.softDelete(instantKst(2030, 8, 13, 0));
        saveLog(refork, LocalDate.of(2030, 8, 14), false);
        saveLog(refork, LocalDate.of(2030, 8, 15), false); // 재분기 버전 log — 계보 전체로 세면 2/4 = 50%

        WeekMetric batchWeek = metricsService.getMetrics(3).weeks().get(2);

        assertThat(batchWeek.effectMeasuredCount()).isEqualTo(1);
        assertThat(batchWeek.avgCompletionDeltaPp()).isEqualTo(50.0); // 100% − 50%, 재분기 log 섞이면 0.0
    }

    @Test
    void 계보가_통째로_삭제된_수락_건은_측정_불가로_센다() {
        User user = userRepository.save(User.signUp("rec-metrics-deleted@rougether.dev"));
        Routine routine = saveRoutine(user, "삭제될 루틴");
        saveLog(routine, LocalDate.of(2030, 7, 28), true);
        saveLog(routine, LocalDate.of(2030, 8, 11), true);
        RoutineRecommendation accepted = saveRecommendation(user, routine, BATCH_WEEK, instantKst(2030, 8, 11, 0));
        accepted.accept(instantKst(2030, 8, 5, 9), routine.getId());
        routine.softDelete(instantKst(2030, 8, 18, 0));

        WeekMetric batchWeek = metricsService.getMetrics(3).weeks().get(2);

        assertThat(batchWeek.effectMeasuredCount()).isZero();
        assertThat(batchWeek.effectUnmeasurableCount()).isEqualTo(1);
        assertThat(batchWeek.avgCompletionDeltaPp()).isNull();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 관측_API_는_주차_퍼널_JSON_을_돌려준다() throws Exception {
        mockMvc.perform(get("/admin/recommendations/metrics").param("weeks", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weeks.length()").value(1))
                .andExpect(jsonPath("$.weeks[0].weekStartDate").value(CURRENT_WEEK.toString()))
                .andExpect(jsonPath("$.weeks[0].weekEndDate").value(CURRENT_WEEK.plusDays(6).toString()))
                .andExpect(jsonPath("$.weeks[0].inProgress").value(true))
                .andExpect(jsonPath("$.weeks[0].createdCount").value(0))
                .andExpect(jsonPath("$.weeks[0].acceptedRate").value(0.0))
                .andExpect(jsonPath("$.weeks[0].avgCompletionDeltaPp").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.generatedAt").value(NOW.toString()));
    }

    @Test
    void 조회_주_수는_1_이상_26_이하로_묶인다() {
        assertThat(metricsService.getMetrics(999).weeks()).hasSize(AdminRecommendationMetricsService.MAX_WEEKS);
        assertThat(metricsService.getMetrics(0).weeks()).hasSize(1);
    }

    @Test
    void 미인증이면_관측_API_에_접근할_수_없다() throws Exception {
        mockMvc.perform(get("/admin/recommendations/metrics"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(username = "observer", roles = "ADMIN")
    void AI_추천_관측_페이지를_렌더링한다() throws Exception {
        mockMvc.perform(get("/recommendations"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AI 조정 추천 퍼널 관측")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/recommendations/metrics")));
    }

    private Routine saveRoutine(User user, String title) {
        return routineRepository.save(Routine.create(
                user, null, title, AuthType.CHECK, "DAILY", "[]", null,
                LocalDate.of(2030, 7, 1), null));
    }

    // 실제 생성 경로처럼 계보 루트를 지정함 - copyAsNewVersion 이 origin 을 승계해야 하는 버전 분기 fixture 용
    private Routine saveLineageRoot(User user, String title) {
        Routine routine = saveRoutine(user, title);
        routine.assignOriginToSelf();
        return routine;
    }

    private void saveLog(Routine routine, LocalDate routineDate, boolean completed) {
        routineLogRepository.save(completed
                ? RoutineLog.complete(routine, routineDate, routineDate.atStartOfDay(KST).toInstant(),
                        CurrencyType.COIN, 10)
                : RoutineLog.fail(routine, routineDate));
    }

    // auditing 이 채우는 created_at 을 원하는 생성 주(일요일 05:00 KST)로 옮겨 주 버킷을 고정함
    private RoutineRecommendation saveRecommendation(User user, Routine routine, LocalDate createdWeekStart,
                                                     Instant expiresAt) {
        RoutineRecommendation recommendation = routineRecommendationRepository.saveAndFlush(
                RoutineRecommendation.rule(user, routine.getId(), routine.getId(),
                        RecommendationType.ADJUST_DAYS, PROPOSAL, "조정 제안", expiresAt));
        jdbcTemplate.update("update routine_recommendations set created_at = ? where id = ?",
                Timestamp.from(instantKst(createdWeekStart, 5)), recommendation.getId());
        return recommendation;
    }

    private static Instant instantKst(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(KST).toInstant();
    }

    private static Instant instantKst(int year, int month, int day, int hour) {
        return instantKst(LocalDate.of(year, month, day), hour);
    }
}
