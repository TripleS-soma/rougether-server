package com.triples.rougether.userapi.appicon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.appicon.AppIconPolicy;
import com.triples.rougether.domain.appicon.AppIconState;
import com.triples.rougether.domain.appicon.entity.UserAppActivity;
import com.triples.rougether.domain.appicon.repository.UserAppActivityRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.Streak;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.appicon.service.AppIconService;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.MemberRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

// 운영과 같은 MySQL에서 foreground 생성 잠금·완료 조회·JWT 주체를 검증함.
@SpringBootTest
@AutoConfigureMockMvc
class AppIconIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-06T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 6);

    @Autowired private AppIconService service;
    @Autowired private UserRepository users;
    @Autowired private UserAppActivityRepository activities;
    @Autowired private RoutineRepository routines;
    @Autowired private RoutineLogRepository logs;
    @Autowired private TodoRepository todos;
    @Autowired private StreakRepository streaks;
    @Autowired private TokenService tokens;
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;
    @MockitoBean(name = "kstClock") private Clock clock;

    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void setClock() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(AppIconPolicy.KST);
    }

    @AfterEach
    void cleanup() {
        for (Long userId : userIds) {
            jdbc.update("delete from user_app_activity where user_id = ?", userId);
            jdbc.update("delete from user_daily_activity where user_id = ?", userId);
            jdbc.update("delete from streaks where user_id = ?", userId);
            jdbc.update("delete from routine_logs where routine_id in (select id from routines where user_id = ?)", userId);
            jdbc.update("delete from routines where user_id = ?", userId);
            jdbc.update("delete from todos where user_id = ?", userId);
            jdbc.update("delete from users where id = ?", userId);
        }
    }

    @Test
    void 두_API는_유효한_인증이_필요하다() throws Exception {
        mvc.perform(get("/api/v1/me/app-icon"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
        mvc.perform(post("/api/v1/me/app-activity"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 조회와_토큰접속은_실제_활동을_갱신하지_않으며_복귀는_즉시_기본으로_회복한다() throws Exception {
        User user = human();
        Instant before = NOW.minus(Duration.ofDays(8));
        activity(user, before);
        tx.executeWithoutResult(ignored -> users.findById(user.getId()).orElseThrow().recordAccess(NOW));

        mvc.perform(get("/api/v1/me/app-icon").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("SOBBING"))
                .andExpect(jsonPath("$.message").value("언제 돌아오냥… 기다리고 있다냥."))
                .andExpect(jsonPath("$.lastForegroundAt").value(before.toString()));
        assertThat(activities.findById(user.getId()).orElseThrow().getLastForegroundAt()).isEqualTo(before);

        mvc.perform(post("/api/v1/me/app-activity").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NORMAL"))
                .andExpect(jsonPath("$.lastForegroundAt").value(NOW.toString()))
                .andExpect(jsonPath("$.nextEvaluationAt").value(NOW.plus(Duration.ofDays(2)).toString()));
    }

    @Test
    void 최초_활동전에는_기본이며_시각과_사용자_id를_클라이언트가_조작하지_못한다() throws Exception {
        User caller = human();
        User other = human();
        assertThat(service.get(caller.getId()).lastForegroundAt()).isNull();
        mvc.perform(post("/api/v1/me/app-activity")
                        .header(HttpHeaders.AUTHORIZATION, bearer(caller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + other.getId() + ",\"at\":\"2099-01-01T00:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastForegroundAt").value(NOW.toString()));
        assertThat(activities.existsById(other.getId())).isFalse();
    }

    @Test
    void 과거_루틴을_오늘_보충한_것은_제외하고_당일_완료와_취소에_반응한다() {
        User user = human();
        Routine routine = routines.save(Routine.create(user, null, "스트레칭", AuthType.CHECK,
                "DAILY", null, null, TODAY.minusDays(10), null));
        logs.save(RoutineLog.complete(routine, TODAY.minusDays(1), NOW, CurrencyType.COIN, 0));
        assertThat(service.get(user.getId()).state()).isEqualTo(AppIconState.NORMAL);

        RoutineLog todayLog = logs.save(RoutineLog.complete(routine, TODAY, NOW, CurrencyType.COIN, 0));
        assertThat(service.get(user.getId()).state()).isEqualTo(AppIconState.DAILY_SUCCESS);
        logs.deleteById(todayLog.getId());
        assertThat(service.get(user.getId()).state()).isEqualTo(AppIconState.NORMAL);
    }

    @Test
    void 무기한_투두도_오늘_실제로_완료하면_웃고_취소하면_되돌아간다() {
        User user = human();
        Todo todo = Todo.create(user, null, "책 한 쪽", null, null, null);
        todo.complete(CurrencyType.COIN, 0, NOW);
        todo = todos.save(todo);
        assertThat(service.get(user.getId()).state()).isEqualTo(AppIconState.DAILY_SUCCESS);
        todo.cancelComplete();
        todos.save(todo);
        assertThat(service.get(user.getId()).completedToday()).isFalse();
    }

    @Test
    void 투두의_오늘_판정은_한국_자정_이상_다음_자정_미만이다() {
        User user = human();
        Instant start = TODAY.atStartOfDay(AppIconPolicy.KST).toInstant();
        Todo previous = Todo.create(user, null, "전날 완료", null, TODAY, null);
        previous.complete(CurrencyType.COIN, 0, start.minusSeconds(1));
        todos.save(previous);
        assertThat(service.get(user.getId()).completedToday()).isFalse();

        Todo next = Todo.create(user, null, "내일 완료", null, TODAY, null);
        next.complete(CurrencyType.COIN, 0, start.plus(Duration.ofDays(1)));
        todos.save(next);
        assertThat(service.get(user.getId()).completedToday()).isFalse();

        Todo today = Todo.create(user, null, "자정 완료", null, TODAY.minusDays(2), null);
        today.complete(CurrencyType.COIN, 0, start);
        todos.save(today);
        assertThat(service.get(user.getId()).state()).isEqualTo(AppIconState.DAILY_SUCCESS);
    }

    @Test
    void 어제_7일에_도달한_왕관은_오늘_마감까지_유지하고_지난_저장값은_만료된다() {
        User user = human();
        Streak streak = Streak.start(user, TODAY.minusDays(7));
        for (int day = 6; day >= 1; day--) {
            streak.applySuccess(TODAY.minusDays(day));
        }
        streaks.save(streak);
        assertThat(service.get(user.getId()).state()).isEqualTo(AppIconState.STREAK_CHAMPION);
        assertThat(service.get(user.getId()).nextEvaluationAt()).isEqualTo(Instant.parse("2026-09-06T15:00:00Z"));

        when(clock.instant()).thenReturn(Instant.parse("2026-09-06T15:00:00Z"));
        assertThat(service.get(user.getId()).state()).isEqualTo(AppIconState.NORMAL);
        assertThat(service.get(user.getId()).currentStreak()).isZero();
    }

    @Test
    void 봇과_탈퇴회원은_조회와_기록을_거절한다() throws Exception {
        User bot = users.save(User.bot("app-icon-api-bot", "고양이 봇", "봇"));
        userIds.add(bot.getId());
        User withdrawn = human();
        withdrawn.softDelete(NOW);
        users.save(withdrawn);
        for (User user : List.of(bot, withdrawn)) {
            mvc.perform(get("/api/v1/me/app-icon").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/me/app-activity").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                    .andExpect(status().isNotFound());
            assertThat(activities.existsById(user.getId())).isFalse();
        }
    }

    @Test
    void 동시에_처음_방문해도_활동은_한_행만_생긴다() throws Exception {
        User user = human();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return service.recordForeground(user.getId()); });
            var second = executor.submit(() -> { start.await(); return service.recordForeground(user.getId()); });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).state()).isEqualTo(AppIconState.NORMAL);
            assertThat(second.get(10, TimeUnit.SECONDS).state()).isEqualTo(AppIconState.NORMAL);
        }
        assertThat(activities.findById(user.getId()).orElseThrow().getLastForegroundAt()).isEqualTo(NOW);
    }

    private User human() {
        User user = users.save(User.signUp());
        userIds.add(user.getId());
        return user;
    }

    private void activity(User user, Instant at) {
        tx.executeWithoutResult(ignored -> activities.save(UserAppActivity.start(
                users.findByIdForUpdate(user.getId()).orElseThrow(), at)));
    }

    private String bearer(User user) {
        return "Bearer " + tokens.issueAccessToken(user.getId(), MemberRole.NORMAL);
    }
}
