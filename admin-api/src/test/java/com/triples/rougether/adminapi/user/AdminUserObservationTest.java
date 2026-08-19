package com.triples.rougether.adminapi.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.goal.entity.Goal;
import com.triples.rougether.domain.goal.entity.UserGoal;
import com.triples.rougether.domain.goal.repository.GoalRepository;
import com.triples.rougether.domain.goal.repository.UserGoalRepository;
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
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminUserObservationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    GoalRepository goalRepository;

    @Autowired
    UserGoalRepository userGoalRepository;

    @Autowired
    CharacterRepository characterRepository;

    @Autowired
    UserCharacterRepository userCharacterRepository;

    @Autowired
    RoutineRepository routineRepository;

    @Autowired
    RoutineLogRepository routineLogRepository;

    @Autowired
    TodoRepository todoRepository;

    @Autowired
    HouseRepository houseRepository;

    @Autowired
    HouseMemberRepository houseMemberRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @WithMockUser(roles = "ADMIN")
    void 요약은_활성회원의_최근가입_최근인증접속_온보딩완료를_집계한다() throws Exception {
        User completed = userRepository.save(User.signUp("observation-summary-completed@rougether.dev"));
        completed.recordAccess(Instant.now());
        completeOnboarding(completed);
        Character duplicateSelectedCharacter = characterRepository.save(new Character(
                "observation_character_duplicate_" + completed.getId(),
                "중복 대표 캐릭터",
                "characters/observation-duplicate.webp",
                9500 + completed.getId().intValue(),
                true));
        userCharacterRepository.save(UserCharacter.createSelected(completed, duplicateSelectedCharacter));
        userRepository.save(User.signUp("observation-summary-incomplete@rougether.dev"));

        mockMvc.perform(get("/admin/users/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeUsers").value(2))
                .andExpect(jsonPath("$.newUsersLast7Days").value(2))
                .andExpect(jsonPath("$.recentlyAccessedLast7Days").value(1))
                .andExpect(jsonPath("$.onboardingCompletedUsers").value(1))
                .andExpect(jsonPath("$.onboardingCompletionRate").value(50.0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 목록은_관측필터와_페이지단위_활성화지표를_반환한다() throws Exception {
        User target = userRepository.save(User.signUp("observation-row-target@rougether.dev"));
        target.recordAccess(Instant.now());
        completeOnboarding(target);

        Routine routine = routineRepository.save(Routine.create(
                target, null, "아침 루틴", AuthType.CHECK, "DAILY", "[]", null,
                LocalDate.now(KST), null));
        routineLogRepository.save(RoutineLog.complete(
                routine, LocalDate.now(KST), Instant.now(), CurrencyType.COIN, 10));

        Todo todo = Todo.create(target, null, "오늘 투두", null, LocalDate.now(KST), null);
        todo.complete(CurrencyType.COIN, 10, Instant.now());
        todoRepository.save(todo);

        House house = houseRepository.save(House.create(
                target, "관측 집", null, null, 4, "OBSERVE1", Instant.now().plusSeconds(86400)));
        houseMemberRepository.save(HouseMember.create(house, target, HouseMemberRole.OWNER));

        mockMvc.perform(get("/admin/users")
                        .param("query", "observation-row-target")
                        .param("accountStatus", "ACTIVE")
                        .param("activity", "WITHIN_7_DAYS")
                        .param("onboarding", "COMPLETED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].lastAccessedAt").exists())
                .andExpect(jsonPath("$.items[0].onboardingCompleted").value(true))
                .andExpect(jsonPath("$.items[0].activeRoutineCount").value(1))
                .andExpect(jsonPath("$.items[0].activeHouseCount").value(1))
                .andExpect(jsonPath("$.items[0].recentCompletionCount").value(2));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 탈퇴회원과_미접속_온보딩미완료_회원을_각각_필터링한다() throws Exception {
        userRepository.save(User.signUp("observation-never-accessed@rougether.dev"));
        User withdrawn = userRepository.save(User.signUp("observation-withdrawn@rougether.dev"));
        withdrawn.softDelete(Instant.now());
        withdrawn.anonymize();

        mockMvc.perform(get("/admin/users")
                        .param("query", "observation-never-accessed")
                        .param("accountStatus", "ACTIVE")
                        .param("activity", "NEVER")
                        .param("onboarding", "INCOMPLETE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].lastAccessedAt").doesNotExist())
                .andExpect(jsonPath("$.items[0].onboardingCompleted").value(false));

        mockMvc.perform(get("/admin/users")
                        .param("accountStatus", "WITHDRAWN")
                        .param("activity", "ALL")
                        .param("onboarding", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].userId").value(withdrawn.getId()))
                .andExpect(jsonPath("$.items[0].accountStatus").value("WITHDRAWN"))
                .andExpect(jsonPath("$.items[0].deletedAt").exists());
    }

    @Test
    void 미인증이면_관측요약에_접근할_수_없다() throws Exception {
        mockMvc.perform(get("/admin/users/summary"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(username = "observer", roles = "ADMIN")
    void 유저관리페이지는_운영관측_요약과_필터를_렌더링한다() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("운영 관측")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("최근 인증 접속")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("온보딩 상태")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 동거_봇은_요약과_활성_탈퇴_전체_목록에서_빠지고_BOT_상태로만_조회된다() throws Exception {
        User human = userRepository.save(User.signUp("observation-human@rougether.dev"));
        human.recordAccess(Instant.now());
        completeOnboarding(human);
        User bot = userRepository.save(User.bot("observation-bot", "관측봇", "요약에 섞이면 안 됨"));
        bot.recordAccess(Instant.now());
        completeOnboarding(bot); // 시드가 채우는 온보딩 조건 — 그래도 KPI 에서 빠져야 한다

        mockMvc.perform(get("/admin/users/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeUsers").value(1))
                .andExpect(jsonPath("$.newUsersLast7Days").value(1))
                .andExpect(jsonPath("$.recentlyAccessedLast7Days").value(1))
                .andExpect(jsonPath("$.onboardingCompletedUsers").value(1));

        for (String status : new String[] {"ACTIVE", "WITHDRAWN", "ALL"}) {
            mockMvc.perform(get("/admin/users").param("query", "관측봇").param("accountStatus", status))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(0));
        }
        mockMvc.perform(get("/admin/users").param("accountStatus", "BOT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].userId").value(bot.getId()))
                .andExpect(jsonPath("$.items[0].nickname").value("관측봇"))
                .andExpect(jsonPath("$.items[0].accountStatus").value("BOT"))
                .andExpect(jsonPath("$.items[0].onboardingCompleted").value(true));
        mockMvc.perform(get("/users"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<option value=\"BOT\">봇</option>")));
    }

    private void completeOnboarding(User user) {
        String suffix = String.valueOf(user.getId());
        jdbcTemplate.update(
                "INSERT INTO goals (code, name, sort_order, is_active) VALUES (?, ?, ?, true)",
                "observation_goal_" + suffix, "관측 목표", 9000 + user.getId());
        Long goalId = jdbcTemplate.queryForObject(
                "SELECT id FROM goals WHERE code = ?", Long.class, "observation_goal_" + suffix);
        Goal goal = goalRepository.findById(goalId).orElseThrow();
        userGoalRepository.save(UserGoal.of(user, goal, true));

        Character character = characterRepository.save(new Character(
                "observation_character_" + suffix, "관측 캐릭터", "characters/observation.webp",
                9000 + user.getId().intValue(), true));
        userCharacterRepository.save(UserCharacter.createSelected(user, character));
    }
}
