package com.triples.rougether.adminapi.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.activity.repository.UserDailyActivityRepository;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.Streak;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import jakarta.persistence.EntityManager;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminRetentionMetricsIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2030, 8, 20);
    private static final Instant NOW = LocalDateTime.of(2030, 8, 20, 13, 0).atZone(KST).toInstant();

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
    UserRepository userRepository;

    @Autowired
    UserDailyActivityRepository userDailyActivityRepository;

    @Autowired
    RoutineRepository routineRepository;

    @Autowired
    RoutineLogRepository routineLogRepository;

    @Autowired
    StreakRepository streakRepository;

    @Autowired
    HouseRepository houseRepository;

    @Autowired
    HouseMemberRepository houseMemberRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    EntityManager entityManager;

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실제_저장데이터에서_탈퇴와_봇을_제외하고_개인군과_공동군을_분리한다() throws Exception {
        User personal = user("retention-personal@rougether.dev", TODAY.minusDays(31));
        User sharedA = user("retention-shared-a@rougether.dev", TODAY.minusDays(8));
        User sharedB = user("retention-shared-b@rougether.dev", TODAY.minusDays(1));
        User withdrawn = user("retention-withdrawn@rougether.dev", TODAY.minusDays(31));
        User bot = bot("retention-bot", TODAY.minusDays(31));

        insertActivity(personal, TODAY.minusDays(30));
        insertActivity(personal, TODAY.minusDays(24));
        insertActivity(personal, TODAY.minusDays(1));
        insertActivity(sharedA, TODAY.minusDays(7));
        insertActivity(sharedA, TODAY.minusDays(1));
        insertActivity(sharedB, TODAY);
        insertActivity(withdrawn, TODAY.minusDays(30));
        // 기록 계층 가드를 우회한 이상 데이터가 있어도 KPI 조회에서 봇을 제외해야 함.
        jdbcTemplate.update(
                "INSERT INTO user_daily_activity (user_id, activity_date, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                bot.getId(), TODAY.minusDays(30));

        Routine personalRoutine = routine(personal, "개인 루틴");
        complete(personalRoutine, TODAY.minusDays(6), TODAY.minusDays(6));
        complete(personalRoutine, TODAY.minusDays(3), TODAY.minusDays(3));
        complete(personalRoutine, TODAY.minusDays(1), TODAY);
        fail(personalRoutine, TODAY.minusDays(2));

        Routine sharedRoutine = routine(sharedA, "공동 루틴");
        complete(sharedRoutine, TODAY.minusDays(10), TODAY.minusDays(10));
        complete(sharedRoutine, TODAY.minusDays(6), TODAY.minusDays(6));
        fail(sharedRoutine, TODAY.minusDays(2));

        Routine withdrawnRoutine = routine(withdrawn, "탈퇴 루틴");
        complete(withdrawnRoutine, TODAY.minusDays(6), TODAY.minusDays(6));
        complete(withdrawnRoutine, TODAY.minusDays(3), TODAY.minusDays(3));
        complete(withdrawnRoutine, TODAY.minusDays(1), TODAY);
        Routine botRoutine = routine(bot, "봇 루틴");
        complete(botRoutine, TODAY.minusDays(6), TODAY.minusDays(6));
        complete(botRoutine, TODAY.minusDays(3), TODAY.minusDays(3));
        complete(botRoutine, TODAY.minusDays(1), TODAY);

        Streak personalStreak = Streak.start(personal, TODAY.minusDays(4));
        personalStreak.applySuccess(TODAY.minusDays(3));
        personalStreak.applySuccess(TODAY.minusDays(2));
        personalStreak.applySuccess(TODAY.minusDays(1));
        streakRepository.save(personalStreak);
        streakRepository.save(Streak.start(sharedA, TODAY.minusDays(2))); // stale → 0
        streakRepository.save(Streak.start(withdrawn, TODAY));
        streakRepository.save(Streak.start(bot, TODAY));

        House privateHouse = houseRepository.save(House.createPrivate(
                personal, "기본 집", null, null, 4, "RETENTION-PRIVATE", NOW.plusSeconds(86400)));
        houseMemberRepository.save(HouseMember.create(privateHouse, personal, HouseMemberRole.OWNER));
        houseMemberRepository.save(HouseMember.create(privateHouse, bot, HouseMemberRole.MEMBER));
        House sharedHouse = houseRepository.save(House.createPrivate(
                sharedA, "실사용자 공동 집", null, null, 4, "RETENTION-SHARED", NOW.plusSeconds(86400)));
        houseMemberRepository.save(HouseMember.create(sharedHouse, sharedA, HouseMemberRole.OWNER));
        houseMemberRepository.save(HouseMember.create(sharedHouse, sharedB, HouseMemberRole.MEMBER));
        HouseMember leftPeer = houseMemberRepository.save(
                HouseMember.create(privateHouse, sharedB, HouseMemberRole.MEMBER));
        leftPeer.leave();
        houseMemberRepository.save(HouseMember.create(privateHouse, withdrawn, HouseMemberRole.MEMBER));
        House deletedHouse = houseRepository.save(House.createPrivate(
                personal, "삭제된 공동 집", null, null, 4, "RETENTION-DELETED", NOW.plusSeconds(86400)));
        houseMemberRepository.save(HouseMember.create(deletedHouse, personal, HouseMemberRole.OWNER));
        houseMemberRepository.save(HouseMember.create(deletedHouse, sharedB, HouseMemberRole.MEMBER));
        deletedHouse.softDelete();

        withdrawn.softDelete(NOW.minusSeconds(1));
        withdrawn.anonymize();
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/admin/retention/metrics").param("cohortDays", "35"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfDate").value(TODAY.toString()))
                // 이 테스트 DB 에는 BATCH_* 메타데이터가 없어 확정 정보 없음(null) - 완료율 창은 잘리지 않는다.
                .andExpect(jsonPath("$.dayEndFinalizedThroughDate").isEmpty())
                .andExpect(jsonPath("$.retention.d1.returnedUserCount").value(3))
                .andExpect(jsonPath("$.retention.d1.eligibleUserCount").value(3))
                .andExpect(jsonPath("$.retention.d7.returnedUserCount").value(2))
                .andExpect(jsonPath("$.retention.d7.eligibleUserCount").value(2))
                .andExpect(jsonPath("$.retention.d30.returnedUserCount").value(1))
                .andExpect(jsonPath("$.retention.d30.eligibleUserCount").value(1))
                .andExpect(jsonPath("$.retentionCohorts.length()").value(3))
                .andExpect(jsonPath("$.northStar.qualifiedUserCount").value(1))
                .andExpect(jsonPath("$.northStar.registeredUserCount").value(3))
                .andExpect(jsonPath("$.northStar.percentage").value(33.3))
                .andExpect(jsonPath("$.segments[0].segment").value("OVERALL"))
                .andExpect(jsonPath("$.segments[0].userCount").value(3))
                .andExpect(jsonPath("$.segments[0].completionRate.completedLogCount").value(5))
                .andExpect(jsonPath("$.segments[0].completionRate.decidedLogCount").value(7))
                .andExpect(jsonPath("$.segments[0].completionRate.percentage").value(71.4))
                .andExpect(jsonPath("$.segments[0].currentStreak.currentStreakSum").value(4))
                .andExpect(jsonPath("$.segments[0].currentStreak.userCount").value(3))
                .andExpect(jsonPath("$.segments[0].currentStreak.average").value(1.3))
                .andExpect(jsonPath("$.segments[0].restartRate.restartedUserCount").value(1))
                .andExpect(jsonPath("$.segments[0].restartRate.gapExperiencedUserCount").value(1))
                .andExpect(jsonPath("$.segments[1].segment").value("PERSONAL"))
                .andExpect(jsonPath("$.segments[1].userCount").value(1))
                .andExpect(jsonPath("$.segments[1].completionRate.percentage").value(75.0))
                .andExpect(jsonPath("$.segments[2].segment").value("SHARED"))
                .andExpect(jsonPath("$.segments[2].userCount").value(2))
                .andExpect(jsonPath("$.segments[2].completionRate.percentage").value(66.7));

        assertThat(userDailyActivityRepository.countByUserIdAndActivityDate(personal.getId(), TODAY.minusDays(30)))
                .isEqualTo(1);
    }

    @Test
    void 미인증은_로그인으로_이동한다() throws Exception {
        mockMvc.perform(get("/admin/retention/metrics"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/users"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void 일반_사용자는_API와_화면에_접근할_수_없다() throws Exception {
        mockMvc.perform(get("/admin/retention/metrics"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 코호트_조회일수가_숫자가_아니면_공통_400_오류를_반환한다() throws Exception {
        mockMvc.perform(get("/admin/retention/metrics").param("cohortDays", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("입력값이 올바르지 않습니다."));
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void 슈퍼관리자는_KPI_API와_화면에_접근할_수_있다() throws Exception {
        mockMvc.perform(get("/admin/retention/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.northStar.registeredUserCount").value(0));
        mockMvc.perform(get("/users"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("리텐션·핵심 행동 KPI")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/retention/metrics")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("가입일별 exact-day Retention")));
    }

    private User user(String email, LocalDate signupDate) {
        User user = User.signUp(email);
        ReflectionTestUtils.setField(user, "createdAt", signupDate.atStartOfDay(KST).toInstant());
        User saved = userRepository.saveAndFlush(user);
        jdbcTemplate.update("UPDATE users SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(signupDate.atStartOfDay(KST).toInstant()), saved.getId());
        return saved;
    }

    private User bot(String botKey, LocalDate signupDate) {
        User user = User.bot(botKey, "리텐션 봇", "KPI 제외 대상");
        ReflectionTestUtils.setField(user, "createdAt", signupDate.atStartOfDay(KST).toInstant());
        User saved = userRepository.saveAndFlush(user);
        jdbcTemplate.update("UPDATE users SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(signupDate.atStartOfDay(KST).toInstant()), saved.getId());
        return saved;
    }

    private void insertActivity(User user, LocalDate activityDate) {
        assertThat(userDailyActivityRepository.insertIfActiveUser(user.getId(), activityDate)).isEqualTo(1);
    }

    private Routine routine(User user, String name) {
        return routineRepository.save(Routine.create(
                user, null, name, AuthType.CHECK, "DAILY", "[]", null,
                TODAY.minusDays(60), null));
    }

    private void complete(Routine routine, LocalDate routineDate, LocalDate completedDate) {
        routineLogRepository.save(RoutineLog.complete(
                routine, routineDate, completedDate.atTime(12, 0).atZone(KST).toInstant(),
                CurrencyType.COIN, 10));
    }

    private void fail(Routine routine, LocalDate routineDate) {
        routineLogRepository.save(RoutineLog.fail(routine, routineDate));
    }
}
