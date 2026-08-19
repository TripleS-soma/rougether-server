package com.triples.rougether.userapi.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.policy.SignupWalletPolicy;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.RoutineStatus;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.house.dto.HouseListResponse.HouseSummary;
import com.triples.rougether.userapi.house.service.HouseQueryService;
import com.triples.rougether.userapi.invite.error.InviteErrorCode;
import com.triples.rougether.userapi.invite.service.InviteService;
import com.triples.rougether.userapi.notification.message.NotificationContent;
import com.triples.rougether.userapi.notification.service.NotificationService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// 동거 봇 격리(#308): 초대 보상·알림·집 탐색·주간 회고 대상·리마인더 대상에서 봇이 빠지는지 본다.
@SpringBootTest
@Transactional
class BotIsolationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired UserRepository userRepository;
    @Autowired UserWalletRepository userWalletRepository;
    @Autowired InviteService inviteService;
    @Autowired NotificationService notificationService;
    @Autowired HouseQueryService houseQueryService;
    @Autowired HouseRepository houseRepository;
    @Autowired HouseMemberRepository houseMemberRepository;
    @Autowired RoutineRepository routineRepository;
    @Autowired RoutineLogRepository routineLogRepository;
    @Autowired TodoRepository todoRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 봇은_초대코드를_발급받지_못하고_초대자_피초대자_어느_쪽으로도_보상을_받지_못한다() {
        User human = userWithWallet("isolation-invite-human@rougether.dev");
        User bot = botWithWallet("isolation-invite-bot");
        String humanCode = inviteService.getMyCode(human.getId()).code();

        assertThatThrownBy(() -> inviteService.getMyCode(bot.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(InviteErrorCode.INVITE_BOT_NOT_ALLOWED);

        // 피초대자가 봇
        assertThatThrownBy(() -> inviteService.redeem(bot.getId(), humanCode))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(InviteErrorCode.INVITE_BOT_NOT_ALLOWED);
        assertThat(coin(human.getId())).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE);
        assertThat(coin(bot.getId())).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE);

        // 초대자가 봇(코드가 어떻게든 존재하는 경우) — 사람이 써도 거부
        jdbcTemplate.update("INSERT INTO user_invite_codes (user_id, code, created_at) VALUES (?, 'BOTCODE1', ?)",
                bot.getId(), java.sql.Timestamp.from(Instant.now()));
        User redeemer = userWithWallet("isolation-invite-redeemer@rougether.dev");
        assertThatThrownBy(() -> inviteService.redeem(redeemer.getId(), "BOTCODE1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(InviteErrorCode.INVITE_BOT_NOT_ALLOWED);
        assertThat(coin(redeemer.getId())).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE);
        assertThat(coin(bot.getId())).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE);
    }

    @Test
    void 봇_수신자에게는_알림이_저장되지_않는다() {
        User human = userWithWallet("isolation-notify-human@rougether.dev");
        User bot = botWithWallet("isolation-notify-bot");
        NotificationContent content = new NotificationContent(NotificationType.FRIEND_CHEER, "응원", "잘하고 있어!");

        notificationService.send(bot.getId(), content);
        notificationService.send(human.getId(), content);

        assertThat(notificationCount(bot.getId())).isZero();
        assertThat(notificationCount(human.getId())).isEqualTo(1);
    }

    @Test
    void 집_탐색은_사람_ACTIVE_멤버가_없는_집을_노출하지_않는다() {
        User viewer = userWithWallet("isolation-explore-viewer@rougether.dev");
        User human = userWithWallet("isolation-explore-human@rougether.dev");
        User bot = botWithWallet("isolation-explore-bot");
        House botOnly = house(bot, "봇만 남은 집", "BOTONLY1");
        House withHuman = house(human, "사람 있는 집", "HUMAN001");
        houseMemberRepository.save(HouseMember.create(withHuman, bot, HouseMemberRole.MEMBER));

        // goalCode 변형 2개까지 네 쿼리 모두 같은 술어를 타는지 본다.
        jdbcTemplate.update("INSERT INTO goals (code, name, sort_order, is_active) VALUES ('bot_isolation_goal', '격리 목표', 995, true)");
        Long goalId = jdbcTemplate.queryForObject(
                "SELECT id FROM goals WHERE code = 'bot_isolation_goal' ORDER BY id DESC LIMIT 1", Long.class);
        for (House h : List.of(botOnly, withHuman)) {
            jdbcTemplate.update("INSERT INTO house_goals (house_id, goal_id) VALUES (?, ?)", h.getId(), goalId);
        }

        for (boolean excludeJoined : new boolean[] {false, true}) {
            for (String goalCode : new String[] {null, "bot_isolation_goal"}) {
                List<Long> explored = houseQueryService.explore(viewer.getId(), 0, 50, goalCode, excludeJoined)
                        .items().stream().map(HouseSummary::houseId).toList();
                assertThat(explored)
                        .as("goalCode=%s excludeJoined=%s", goalCode, excludeJoined)
                        .contains(withHuman.getId())
                        .doesNotContain(botOnly.getId());
            }
        }
    }

    @Test
    void 주간_회고_생성_대상에서_봇은_빠진다() {
        LocalDate today = LocalDate.now(KST);
        User human = userWithWallet("isolation-report-human@rougether.dev");
        User bot = botWithWallet("isolation-report-bot");
        completedRoutine(human, today);
        completedRoutine(bot, today);

        List<Long> targets = routineLogRepository.findUserIdsWithLogsInPeriod(
                today.minusDays(6), today, List.of(RoutineLogStatus.COMPLETED, RoutineLogStatus.FAILED),
                0L, PageRequest.of(0, 100));

        assertThat(targets).contains(human.getId()).doesNotContain(bot.getId());
    }

    @Test
    void 리마인더_후보_조회는_봇의_루틴과_투두를_건너뛴다() {
        LocalDate today = LocalDate.now(KST);
        LocalTime at = LocalTime.of(7, 30);
        User human = userWithWallet("isolation-remind-human@rougether.dev");
        User bot = botWithWallet("isolation-remind-bot");
        Routine humanRoutine = routineRepository.save(Routine.create(
                human, null, "사람 루틴", AuthType.CHECK, "DAILY", null, at, today.minusDays(1), null));
        routineRepository.save(Routine.create(
                bot, null, "봇 루틴", AuthType.CHECK, "DAILY", null, at, today.minusDays(1), null));
        Todo humanTodo = todoRepository.save(Todo.create(human, null, "사람 투두", null, today, at));
        todoRepository.save(Todo.create(bot, null, "봇 투두", null, today, at));
        Instant dayStart = today.atStartOfDay(KST).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(KST).toInstant();

        List<Routine> routines = routineRepository.findReminderCandidates(
                RoutineStatus.ACTIVE, at, today, RoutineLogStatus.COMPLETED, NotificationType.ROUTINE_REMINDER,
                dayStart, dayEnd, 0L, PageRequest.of(0, 100));
        List<Todo> todos = todoRepository.findReminderCandidates(
                TodoStatus.PENDING, today, at, NotificationType.TODO_REMINDER,
                dayStart, dayEnd, 0L, PageRequest.of(0, 100));

        assertThat(routines).extracting(Routine::getId).contains(humanRoutine.getId());
        assertThat(routines).extracting(routine -> routine.getUser().getId()).doesNotContain(bot.getId());
        assertThat(todos).extracting(Todo::getId).contains(humanTodo.getId());
        assertThat(todos).extracting(todo -> todo.getUser().getId()).doesNotContain(bot.getId());
    }

    private User userWithWallet(String email) {
        User user = userRepository.save(User.signUp(email));
        userWalletRepository.saveAll(SignupWalletPolicy.issueAll(user));
        return user;
    }

    private User botWithWallet(String botKey) {
        User bot = userRepository.save(User.bot(botKey, "격리봇 " + botKey, null));
        userWalletRepository.saveAll(SignupWalletPolicy.issueAll(bot));
        return bot;
    }

    private House house(User owner, String name, String inviteCode) {
        House house = houseRepository.save(House.create(
                owner, name, null, null, 4, inviteCode, Instant.now().plusSeconds(86400)));
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        return house;
    }

    private void completedRoutine(User user, LocalDate date) {
        Routine routine = routineRepository.save(Routine.create(
                user, null, "회고 루틴", AuthType.CHECK, "DAILY", null, null, date.minusDays(7), null));
        routineLogRepository.save(RoutineLog.complete(
                routine, date, date.atStartOfDay(KST).toInstant(), CurrencyType.COIN, 10));
    }

    private int coin(Long userId) {
        return userWalletRepository.findByUserIdAndCurrencyType(userId, CurrencyType.COIN)
                .map(UserWallet::getBalance).orElseThrow();
    }

    private Integer notificationCount(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ?", Integer.class, userId);
    }
}
