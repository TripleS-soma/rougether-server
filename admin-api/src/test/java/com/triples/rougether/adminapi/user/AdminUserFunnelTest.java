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
import java.sql.Timestamp;
import java.time.Duration;
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

// 유저 퍼널(이탈 지점) API 와 화면. 저장소 JPQL 이 실제 엔티티 관계·필터(탈퇴·봇·AI 제외)대로 동작하는지 H2 로 확인함.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminUserFunnelTest {

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
    void 퍼널은_누적_단계와_공동집_탈퇴를_집계하고_봇과_AI를_제외한다() throws Exception {
        Instant now = Instant.now();
        // 온보딩 → 루틴 생성 → 완료 → 가입 다음날 재접속까지 모두 거친 사용자.
        User activated = userRepository.save(User.signUp("funnel-activated@rougether.dev"));
        activated.recordAccess(now.plus(Duration.ofDays(1)));
        completeOnboarding(activated);
        Routine routine = routineRepository.save(Routine.create(
                activated, null, "아침 루틴", AuthType.CHECK, "DAILY", "[]", null,
                LocalDate.now(KST), null));
        routineLogRepository.save(RoutineLog.complete(
                routine, LocalDate.now(KST), now, CurrencyType.COIN, 10));

        // 온보딩 후 투두만 만들고 완료는 안 한 사용자 - 생성 단계까지.
        User onboardedOnly = userRepository.save(User.signUp("funnel-onboarded@rougether.dev"));
        completeOnboarding(onboardedOnly);
        todoRepository.save(Todo.create(onboardedOnly, null, "할 일", null, LocalDate.now(KST), null));

        // 재접속은 했지만 온보딩이 없는 사용자 - 누적 기준이라 재방문에 세지 않음.
        User signupOnly = userRepository.save(User.signUp("funnel-signup-only@rougether.dev"));
        signupOnly.recordAccess(now.plus(Duration.ofDays(2)));

        House house = houseRepository.save(House.create(
                activated, "퍼널 집", null, null, 4, "FUNNEL01", now.plusSeconds(86400)));
        houseMemberRepository.save(HouseMember.create(house, activated, HouseMemberRole.OWNER));
        houseMemberRepository.save(HouseMember.create(house, onboardedOnly, HouseMemberRole.MEMBER));

        User withdrawn = userRepository.save(User.signUp("funnel-withdrawn@rougether.dev"));
        withdrawn.softDelete(now);
        withdrawn.anonymize();

        // 동거 봇·AI 계정은 온보딩 조건을 채우고 집에 있어도 어느 단계에도 섞이면 안 됨.
        User bot = userRepository.save(User.bot("funnel-bot", "퍼널봇", "집계 제외"));
        bot.recordAccess(now.plus(Duration.ofDays(1)));
        completeOnboarding(bot);
        houseMemberRepository.save(HouseMember.create(house, bot, HouseMemberRole.MEMBER));
        userRepository.save(User.ai("퍼널 AI", "집계 제외"));

        mockMvc.perform(get("/admin/users/funnel").param("signupDays", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signupDays").value(30))
                .andExpect(jsonPath("$.signupPeriod.days").value(30))
                .andExpect(jsonPath("$.stages.length()").value(5))
                .andExpect(jsonPath("$.stages[0].stage").value("SIGNED_UP"))
                .andExpect(jsonPath("$.stages[0].userCount").value(3))
                .andExpect(jsonPath("$.stages[0].signupPercentage").value(100.0))
                .andExpect(jsonPath("$.stages[1].stage").value("ONBOARDED"))
                .andExpect(jsonPath("$.stages[1].userCount").value(2))
                .andExpect(jsonPath("$.stages[1].droppedFromPrevious").value(1))
                .andExpect(jsonPath("$.stages[2].stage").value("ROUTINE_CREATED"))
                .andExpect(jsonPath("$.stages[2].userCount").value(2))
                .andExpect(jsonPath("$.stages[3].stage").value("FIRST_COMPLETED"))
                .andExpect(jsonPath("$.stages[3].userCount").value(1))
                .andExpect(jsonPath("$.stages[3].previousStagePercentage").value(50.0))
                .andExpect(jsonPath("$.stages[4].stage").value("RETURNED"))
                .andExpect(jsonPath("$.stages[4].userCount").value(1))
                .andExpect(jsonPath("$.stages[4].signupPercentage").value(33.3))
                .andExpect(jsonPath("$.biggestDrop.fromStage").value("SIGNED_UP"))
                .andExpect(jsonPath("$.biggestDrop.toStage").value("ONBOARDED"))
                .andExpect(jsonPath("$.biggestDrop.droppedUserCount").value(1))
                .andExpect(jsonPath("$.biggestDrop.dropPercentage").value(33.3))
                .andExpect(jsonPath("$.sharedHouse.userCount").value(2))
                .andExpect(jsonPath("$.sharedHouse.denominatorCount").value(3))
                .andExpect(jsonPath("$.sharedHouse.percentage").value(66.7))
                .andExpect(jsonPath("$.withdrawn.userCount").value(1))
                .andExpect(jsonPath("$.withdrawn.denominatorCount").value(4))
                .andExpect(jsonPath("$.withdrawn.percentage").value(25.0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 첫_완료는_투두_완료도_인정하고_실패_로그와_해제된_캐릭터와_봇뿐인_집과_창_밖_기록은_제외한다() throws Exception {
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(KST);
        // 루틴 로그 없이 투두 완료만으로 첫 완료에 들어가는 사용자.
        User todoDone = userRepository.save(User.signUp("funnel-todo-done@rougether.dev"));
        completeOnboarding(todoDone);
        Todo completedTodo = Todo.create(todoDone, null, "완료한 투두", null, today, null);
        completedTodo.complete(CurrencyType.COIN, 10, now);
        todoRepository.save(completedTodo);

        // FAILED·SKIPPED 로그만 있으면 생성 단계까지만.
        User failedOnly = userRepository.save(User.signUp("funnel-failed-only@rougether.dev"));
        completeOnboarding(failedOnly);
        Routine failedRoutine = routineRepository.save(Routine.create(
                failedOnly, null, "실패 루틴", AuthType.CHECK, "DAILY", "[]", null, today.minusDays(1), null));
        routineLogRepository.save(RoutineLog.fail(failedRoutine, today.minusDays(1)));
        routineLogRepository.save(RoutineLog.skip(failedRoutine, today));

        // 목표는 있지만 대표 캐릭터가 해제된 사용자 - 온보딩 미완료라 루틴을 만들어도 생성 단계에 못 들어감.
        User unselected = userRepository.save(User.signUp("funnel-unselected@rougether.dev"));
        addGoal(unselected);
        userCharacterRepository.save(UserCharacter.create(unselected, newCharacter(unselected)));
        routineRepository.save(Routine.create(
                unselected, null, "온보딩 전 루틴", AuthType.CHECK, "DAILY", "[]", null, today, null));

        // 동거 봇만 있는 집은 공동집이 아님.
        User botMate = userRepository.save(User.signUp("funnel-bot-mate@rougether.dev"));
        completeOnboarding(botMate);
        User bot = userRepository.save(User.bot("funnel-bot-mate-bot", "동거봇", "공동집 판정 제외"));
        House botHouse = houseRepository.save(House.create(
                botMate, "봇과 사는 집", null, null, 4, "FUNNEL02", now.plusSeconds(86400)));
        houseMemberRepository.save(HouseMember.create(botHouse, botMate, HouseMemberRole.OWNER));
        houseMemberRepository.save(HouseMember.create(botHouse, bot, HouseMemberRole.MEMBER));

        // 삭제된 집의 두 사람은 공동집으로 세지 않음.
        House deletedHouse = houseRepository.save(House.create(
                todoDone, "해체된 집", null, null, 4, "FUNNEL03", now.plusSeconds(86400)));
        houseMemberRepository.save(HouseMember.create(deletedHouse, todoDone, HouseMemberRole.OWNER));
        houseMemberRepository.save(HouseMember.create(deletedHouse, failedOnly, HouseMemberRole.MEMBER));
        deletedHouse.softDelete();

        // 창 밖(40일 전) 가입자와 함께 사는 최근 가입자 - 둘 다 공동집이지만 창 안에선 최근 가입자만 셈.
        User recentShared = userRepository.save(User.signUp("funnel-recent-shared@rougether.dev"));
        User oldPeer = userRepository.saveAndFlush(User.signUp("funnel-old-peer@rougether.dev"));
        House sharedHouse = houseRepository.save(House.create(
                recentShared, "같이 사는 집", null, null, 4, "FUNNEL04", now.plusSeconds(86400)));
        houseMemberRepository.save(HouseMember.create(sharedHouse, recentShared, HouseMemberRole.OWNER));
        houseMemberRepository.save(HouseMember.create(sharedHouse, oldPeer, HouseMemberRole.MEMBER));

        // 창 밖(40일 전) 가입 후 탈퇴한 사용자 - 창 안 탈퇴 수에 들어가면 안 됨.
        User oldWithdrawn = userRepository.saveAndFlush(User.signUp("funnel-old-withdrawn@rougether.dev"));
        oldWithdrawn.softDelete(now);
        oldWithdrawn.anonymize();
        userRepository.saveAndFlush(oldWithdrawn);
        Timestamp fortyDaysAgo = Timestamp.from(now.minus(Duration.ofDays(40)));
        jdbcTemplate.update("UPDATE users SET created_at = ? WHERE id IN (?, ?)",
                fortyDaysAgo, oldPeer.getId(), oldWithdrawn.getId());

        mockMvc.perform(get("/admin/users/funnel").param("signupDays", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stages[0].userCount").value(5))
                .andExpect(jsonPath("$.stages[1].userCount").value(3))
                .andExpect(jsonPath("$.stages[2].userCount").value(2))
                .andExpect(jsonPath("$.stages[3].userCount").value(1))
                .andExpect(jsonPath("$.stages[4].userCount").value(0))
                .andExpect(jsonPath("$.biggestDrop.fromStage").value("SIGNED_UP"))
                .andExpect(jsonPath("$.biggestDrop.toStage").value("ONBOARDED"))
                .andExpect(jsonPath("$.biggestDrop.droppedUserCount").value(2))
                .andExpect(jsonPath("$.biggestDrop.dropPercentage").value(40.0))
                .andExpect(jsonPath("$.sharedHouse.userCount").value(1))
                .andExpect(jsonPath("$.sharedHouse.denominatorCount").value(5))
                .andExpect(jsonPath("$.withdrawn.userCount").value(0))
                .andExpect(jsonPath("$.withdrawn.denominatorCount").value(5));

        mockMvc.perform(get("/admin/users/funnel").param("signupDays", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stages[0].userCount").value(6))
                .andExpect(jsonPath("$.sharedHouse.userCount").value(2))
                .andExpect(jsonPath("$.sharedHouse.denominatorCount").value(6))
                .andExpect(jsonPath("$.withdrawn.userCount").value(1))
                .andExpect(jsonPath("$.withdrawn.denominatorCount").value(7));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 가입_기간_창_밖의_가입자는_제외하고_전체_기간에는_포함한다() throws Exception {
        userRepository.save(User.signUp("funnel-recent@rougether.dev"));
        User old = userRepository.saveAndFlush(User.signUp("funnel-old@rougether.dev"));
        jdbcTemplate.update("UPDATE users SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(40))), old.getId());

        mockMvc.perform(get("/admin/users/funnel").param("signupDays", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signupPeriod.days").value(7))
                .andExpect(jsonPath("$.stages[0].userCount").value(1));

        mockMvc.perform(get("/admin/users/funnel").param("signupDays", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signupDays").value(0))
                .andExpect(jsonPath("$.signupPeriod").doesNotExist())
                .andExpect(jsonPath("$.stages[0].userCount").value(2));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 가입_기간을_생략하면_기본_30일로_조회한다() throws Exception {
        mockMvc.perform(get("/admin/users/funnel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signupDays").value(30))
                .andExpect(jsonPath("$.biggestDrop").doesNotExist());
    }

    @Test
    void 미인증이면_퍼널에_접근할_수_없다() throws Exception {
        mockMvc.perform(get("/admin/users/funnel"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(username = "observer", roles = "ADMIN")
    void 유저관리페이지는_퍼널_섹션과_가입기간_선택을_렌더링한다() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("유저 퍼널")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"funnelSignupDays\"")));
    }

    // 온보딩 완료 계약(목표 1개 이상 + 선택된 미삭제 대표 캐릭터)을 채움.
    private void completeOnboarding(User user) {
        addGoal(user);
        userCharacterRepository.save(UserCharacter.createSelected(user, newCharacter(user)));
    }

    private void addGoal(User user) {
        String suffix = String.valueOf(user.getId());
        jdbcTemplate.update(
                "INSERT INTO goals (code, name, sort_order, is_active) VALUES (?, ?, ?, true)",
                "funnel_goal_" + suffix, "퍼널 목표", 9500 + user.getId());
        Long goalId = jdbcTemplate.queryForObject(
                "SELECT id FROM goals WHERE code = ?", Long.class, "funnel_goal_" + suffix);
        Goal goal = goalRepository.findById(goalId).orElseThrow();
        userGoalRepository.save(UserGoal.of(user, goal, true));
    }

    private Character newCharacter(User user) {
        return characterRepository.save(new Character(
                "funnel_character_" + user.getId(), "퍼널 캐릭터", "characters/funnel.webp",
                9500 + user.getId().intValue(), true));
    }
}
