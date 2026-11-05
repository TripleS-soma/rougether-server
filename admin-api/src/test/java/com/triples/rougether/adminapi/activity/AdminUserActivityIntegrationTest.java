package com.triples.rougether.adminapi.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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

@SpringBootTest(properties = "admin.activity.cache-ttl=0s")
@AutoConfigureMockMvc
@Transactional
class AdminUserActivityIntegrationTest {
    private static final LocalDate TODAY = LocalDate.of(2030, 8, 20);
    private static final Instant START = Instant.parse("2030-08-19T15:00:00Z");
    private static final Instant END = START.plusSeconds(86400);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean @Primary Clock activityClock() {
            return Clock.fixed(START.plusSeconds(36000), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RoutineRepository routines;
    @Autowired RoutineLogRepository logs;
    @Autowired TodoRepository todos;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실제완료시각으로_KST반개구간을_집계하며_접속행은_행동수에_더하지않는다() throws Exception {
        User user = human("경계 확인");
        Routine routine = routines.saveAndFlush(Routine.create(user, null, "비공개 루틴 제목", AuthType.CHECK,
                "DAILY", "[]", null, TODAY.minusDays(7), null));
        jdbc.update("UPDATE routines SET created_at = ? WHERE id = ?", ts(START.minusSeconds(100)), routine.getId());
        logs.saveAndFlush(RoutineLog.complete(routine, TODAY.minusDays(7), START, CurrencyType.COIN, 10));
        logs.saveAndFlush(RoutineLog.complete(routine, TODAY.minusDays(6), END.minusSeconds(1), CurrencyType.COIN, 10));
        logs.saveAndFlush(RoutineLog.complete(routine, TODAY, END, CurrencyType.COIN, 10));
        logs.saveAndFlush(RoutineLog.complete(routine, TODAY.minusDays(8), START.minusSeconds(1), CurrencyType.COIN, 10));
        daily(user, TODAY, END.plusSeconds(10)); // 지연 적재 시각은 활동 시각으로 노출하지 않는다.
        jdbc.update("UPDATE users SET last_accessed_at = ? WHERE id = ?", ts(START.plusSeconds(600)), user.getId());
        mvc.perform(get("/admin/user-activities"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.date").value(TODAY.toString()))
                .andExpect(jsonPath("$.timezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.summary.usersWithRecords").value(1))
                .andExpect(jsonPath("$.summary.actionRecords").value(2))
                .andExpect(jsonPath("$.items[0].authenticatedActivity").value(true))
                .andExpect(jsonPath("$.items[0].features.length()").value(1))
                .andExpect(jsonPath("$.items[0].features[0].code").value("ROUTINE_COMPLETE"))
                .andExpect(jsonPath("$.items[0].features[0].recordCount").value(2))
                .andExpect(jsonPath("$.items[0].firstRecordedAt").value(START.toString()))
                .andExpect(jsonPath("$.items[0].lastRecordedAt").value(END.minusSeconds(1).toString()))
                .andExpect(content().string(not(containsString("비공개 루틴 제목"))))
                .andExpect(content().string(not(containsString("@private.test"))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 일별접속만_있으면_시각을추측하지않고_행동없이표시한다() throws Exception {
        User user = human("접속만");
        daily(user, TODAY, END.plusSeconds(30));
        mvc.perform(get("/admin/user-activities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.usersWithActions").value(0))
                .andExpect(jsonPath("$.items[0].authenticatedActivity").value(true))
                .andExpect(jsonPath("$.items[0].lastRecordedAt").doesNotExist())
                .andExpect(jsonPath("$.items[0].features").isEmpty());
        mvc.perform(get("/admin/user-activities").param("actionsOnly", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 봇_AI_탈퇴계정은_접속과행동이있어도제외한다() throws Exception {
        User human = human("사람");
        User bot = users.saveAndFlush(User.bot("activity-bot", "동거봇", "봇"));
        User ai = users.saveAndFlush(User.ai("AI", "AI"));
        User withdrawn = human("탈퇴");
        withdrawn.softDelete(START);
        users.flush();
        for (User user : new User[]{human, bot, ai, withdrawn}) {
            daily(user, TODAY, START);
            wallet(user, "SHOP_PURCHASE", START.plusSeconds(10));
        }
        mvc.perform(get("/admin/user-activities"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].userId").value(human.getId()))
                .andExpect(jsonPath("$.summary.actionRecords").value(1));
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void 검색_기능필터_페이지와요약은_필터에맞는전체사용자를기준으로한다() throws Exception {
        User first = human("테스터 알파");
        User second = human("테스터 베타");
        User onlyAccess = human("접속만");
        wallet(first, "SHOP_PURCHASE", START.plusSeconds(20));
        wallet(first, "GACHA_DRAW", START.plusSeconds(30));
        wallet(second, "SHOP_PURCHASE", START.plusSeconds(40));
        daily(onlyAccess, TODAY, START);
        mvc.perform(get("/admin/user-activities").param("query", "테스터").param("feature", "SHOP_PURCHASE")
                        .param("size", "1").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.summary.usersWithActions").value(2))
                .andExpect(jsonPath("$.summary.actionRecords").value(3))
                .andExpect(jsonPath("$.summary.featuresUsed").value(2))
                .andExpect(jsonPath("$.items[0].userId").value(first.getId()));
        mvc.perform(get("/admin/user-activities").param("feature", "ACCESS"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/admin/user-activities").param("query", second.getId().toString()).param("page", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.items[0].userId").value(second.getId()));
        mvc.perform(get("/admin/user-activities").param("query", "%' OR 1=1 --"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 접속기록없이도_할일완료를발견하고_완료취소는반영한다() throws Exception {
        User user = human("취소 확인");
        Todo todo = Todo.create(user, null, "비공개 할일", "개인 내용", TODAY.minusDays(3), null);
        todo.complete(CurrencyType.COIN, 10, START.plusSeconds(50));
        todos.saveAndFlush(todo);
        jdbc.update("UPDATE todos SET created_at = ? WHERE id = ?", ts(START.minusSeconds(100)), todo.getId());
        mvc.perform(get("/admin/user-activities").param("feature", "TODO_COMPLETE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].authenticatedActivity").value(false))
                .andExpect(jsonPath("$.items[0].features[0].code").value("TODO_COMPLETE"));
        todo.cancelComplete();
        em.flush();
        mvc.perform(get("/admin/user-activities").param("feature", "TODO_COMPLETE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 날짜와기능값을검증하고_과거일자조회도지원한다() throws Exception {
        User user = human("지난 날짜");
        wallet(user, "SHOP_PURCHASE", START.minusSeconds(1));
        mvc.perform(get("/admin/user-activities").param("date", TODAY.minusDays(1).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        for (LocalDate date : new LocalDate[]{TODAY.plusDays(1), TODAY.minusDays(366)}) {
            mvc.perform(get("/admin/user-activities").param("date", date.toString()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ADMIN_ACTIVITY_DATE_INVALID"));
        }
        mvc.perform(get("/admin/user-activities").param("feature", "UNKNOWN"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/user-activities").param("date", "broken"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 별도메뉴를렌더링하고_폐지한악세사리화면은새화면으로연결한다() throws Exception {
        mvc.perform(get("/user-activities"))
                .andExpect(status().isOk()).andExpect(view().name("user-activities"))
                .andExpect(content().string(containsString("사용자 활동")))
                .andExpect(content().string(containsString("href=\"/users\"")))
                .andExpect(content().string(not(containsString("악세사리 관리"))));
        mvc.perform(get("/accessory-render-profiles"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/user-activities"));
    }

    @Test
    void 로그인하지않으면_화면과API에접근할수없다() throws Exception {
        mvc.perform(get("/user-activities")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/user-activities")).andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(roles = "USER")
    void 일반사용자는_화면과API에접근할수없다() throws Exception {
        mvc.perform(get("/user-activities")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/user-activities")).andExpect(status().isForbidden());
    }

    private User human(String nickname) {
        User user = User.signUp("activity-" + java.util.UUID.randomUUID() + "@private.test");
        user.changeNickname(nickname);
        return users.saveAndFlush(user);
    }

    private void daily(User user, LocalDate date, Instant persistedAt) {
        jdbc.update("INSERT INTO user_daily_activity(user_id, activity_date, created_at) VALUES (?, ?, ?)",
                user.getId(), date, ts(persistedAt));
    }

    private void wallet(User user, String reason, Instant at) {
        jdbc.update("INSERT INTO wallet_histories(user_id,currency_type,amount,reason,balance_after,created_at) "
                + "VALUES (?, 'COIN', -10, ?, 90, ?)", user.getId(), reason, ts(at));
    }

    private static Timestamp ts(Instant at) { return Timestamp.from(at); }
}
