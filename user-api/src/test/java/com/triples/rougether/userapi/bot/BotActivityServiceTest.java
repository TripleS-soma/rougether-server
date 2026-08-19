package com.triples.rougether.userapi.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberCheer;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.entity.HouseMission;
import com.triples.rougether.domain.house.entity.HouseMissionType;
import com.triples.rougether.domain.house.repository.HouseMemberCheerRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseMissionDailyContributionRepository;
import com.triples.rougether.domain.house.repository.HouseMissionRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.policy.SignupWalletPolicy;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.repository.PersonalRoomRepository;
import com.triples.rougether.domain.room.repository.RoomItemPlacementRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.entity.RoutineLogStatus;
import com.triples.rougether.domain.routine.entity.Todo;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.domain.routine.repository.TodoRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.global.text.BannedWordChecker;
import com.triples.rougether.userapi.room.dto.RoomLayoutUpdateRequest;
import com.triples.rougether.userapi.room.service.RoomCommandService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

// 동거 봇 활동 틱(#310) 통합 테스트. 행동 1건 = 트랜잭션 1개(실패 격리)를 검증해야 하므로 클래스 @Transactional 없이
// 실제 커밋 후 DB 를 읽고 만든 데이터는 직접 지운다. 결정은 (봇 id, 날짜, 대상 id) 결정론적 난수라 id 가 매 실행 다르므로,
// 픽스처는 "원하는 결정이 나오는 봇/루틴/미션이 잡힐 때까지" 카탈로그 키·항목을 추가하는 방식으로 고정한다.
@SpringBootTest
class BotActivityServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String MARKER = "bot_activity_test";

    @Autowired BotActivityService botActivityService;
    @Autowired BotSocialActions botSocialActions;
    @Autowired ApplicationContext applicationContext;
    @Autowired UserRepository userRepository;
    @Autowired UserWalletRepository userWalletRepository;
    @Autowired HouseRepository houseRepository;
    @Autowired HouseMemberRepository houseMemberRepository;
    @Autowired HouseMemberCheerRepository houseMemberCheerRepository;
    @Autowired HouseMissionRepository houseMissionRepository;
    @Autowired HouseMissionDailyContributionRepository dailyContributionRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired RoutineRepository routineRepository;
    @Autowired RoutineLogRepository routineLogRepository;
    @Autowired TodoRepository todoRepository;
    @Autowired StreakRepository streakRepository;
    @Autowired PersonalRoomRepository personalRoomRepository;
    @Autowired RoomItemPlacementRepository roomItemPlacementRepository;
    @Autowired ThemeRepository themeRepository;
    @Autowired ItemRepository itemRepository;
    @Autowired UserItemRepository userItemRepository;
    @Autowired RoomCommandService roomCommandService;
    @Autowired BannedWordChecker bannedWordChecker;
    @Autowired JdbcTemplate jdbcTemplate;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> houseIds = new ArrayList<>();
    private Long themeId;

    @AfterEach
    void cleanup() {
        for (Long houseId : houseIds) {
            jdbcTemplate.update("DELETE FROM house_mission_daily_contributions WHERE mission_id IN (SELECT id FROM house_missions WHERE house_id = ?)", houseId);
            jdbcTemplate.update("DELETE FROM house_mission_daily_rewards WHERE mission_id IN (SELECT id FROM house_missions WHERE house_id = ?)", houseId);
            jdbcTemplate.update("DELETE FROM house_mission_participants WHERE mission_id IN (SELECT id FROM house_missions WHERE house_id = ?)", houseId);
            jdbcTemplate.update("DELETE FROM house_missions WHERE house_id = ?", houseId);
            jdbcTemplate.update("DELETE FROM house_member_cheers WHERE house_id = ?", houseId);
            jdbcTemplate.update("DELETE FROM room_guestbooks WHERE house_id = ?", houseId);
            jdbcTemplate.update("DELETE FROM house_members WHERE house_id = ?", houseId);
            jdbcTemplate.update("DELETE FROM house WHERE id = ?", houseId);
        }
        for (Long userId : userIds) {
            jdbcTemplate.update("DELETE FROM routine_logs WHERE routine_id IN (SELECT id FROM routines WHERE user_id = ?)", userId);
            jdbcTemplate.update("DELETE FROM routines WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM todos WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM categories WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM streaks WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM room_cobwebs WHERE room_user_id = ? OR cleaned_by_user_id = ?", userId, userId);
            jdbcTemplate.update("DELETE FROM room_item_placements WHERE room_user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM room_surface_slots WHERE room_user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM personal_rooms WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_items WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM notification WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM wallet_histories WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_wallets WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        if (themeId != null) {
            jdbcTemplate.update("DELETE FROM items WHERE theme_id = ?", themeId);
            jdbcTemplate.update("DELETE FROM themes WHERE id = ?", themeId);
        }
    }

    @Test
    void 사람이_있는_집에서만_루틴을_완료하고_코인은_일일_상한_안에서만_지급되며_미션_기여는_하루_1회다() {
        LocalDate today = LocalDate.now(KST);
        User bot = botNotResting(today, profile -> true);
        BotActivityProfile profile = profileOf(bot);
        int tick = lastActiveTick(profile);
        ZonedDateTime at = today.atTime(timeOfTick(tick)).atZone(KST);

        User owner = human("act-owner");
        House withHuman = house(owner, 4);
        join(withHuman, bot);
        User absentOwner = human("act-absent"); // 집만 만들고 사람 멤버십은 두지 않는다 → 사람 0명 집
        House withoutHuman = houseWithoutOwnerMembership(absentOwner, 4);
        join(withoutHuman, bot);

        // 루틴: 오늘 목표 틱이 잡힌 것 3개 이상 + 안 잡힌 것(있으면) 확보
        Category category = categoryRepository.save(Category.create(bot, "봇 카테고리", "#FFFFFF", null, 0, PrivacyScope.PUBLIC));
        List<Routine> due = new ArrayList<>();
        List<Routine> notDue = new ArrayList<>();
        for (int i = 0; i < 40 && due.size() < 3; i++) {
            Routine routine = routineRepository.save(Routine.create(
                    bot, category, "봇 루틴 " + i, AuthType.CHECK, "DAILY", null, null, today.minusDays(1), null));
            routine.assignOriginToSelf();
            routineRepository.save(routine);
            if (BotDecision.isDue(BotDecision.routineCompletionTick(bot.getId(), today, routine.getId(), profile), tick)) {
                due.add(routine);
            } else {
                notDue.add(routine);
            }
        }
        assertThat(due).hasSizeGreaterThanOrEqualTo(3);
        // 오늘 이미 45코인을 받은 상태로 만든다 → 상한(50)까지 5코인만 남음
        Todo filler = Todo.create(bot, category, "상한 채우기", null, today, null);
        filler.complete(CurrencyType.COIN, 45, Instant.now().minusSeconds(60));
        todoRepository.save(filler);
        long walletBefore = coinBalance(bot.getId());

        // 미션: 사람 있는 집에 목표 틱이 잡힌 미션 1개(+ 안 잡힌 것), 사람 없는 집에도 하나
        HouseMission dueMission = missionUntil(withHuman, mission ->
                BotDecision.isDue(BotDecision.missionContributionTick(bot.getId(), today, mission.getId(), profile), tick));
        HouseMission absentMission = missionUntil(withoutHuman, mission ->
                BotDecision.isDue(BotDecision.missionContributionTick(bot.getId(), today, mission.getId(), profile), tick));

        BotTickReport report = botActivityService.runTick(at);

        assertThat(report.failures()).isZero();
        assertThat(report.routinesCompleted()).isEqualTo(due.size());
        for (Routine routine : due) {
            assertThat(routineLogRepository.findByRoutineIdAndRoutineDateAndStatus(
                    routine.getId(), today, RoutineLogStatus.COMPLETED)).isPresent();
        }
        for (Routine routine : notDue) {
            assertThat(routineLogRepository.findByRoutineIdAndRoutineDateAndStatus(
                    routine.getId(), today, RoutineLogStatus.COMPLETED)).isEmpty();
        }
        int rewardedTotal = routineLogRepository.sumRewardAmountByRoutine_UserIdAndRoutineDateAndStatus(
                bot.getId(), today, RoutineLogStatus.COMPLETED);
        assertThat(rewardedTotal).isEqualTo(5); // 첫 완료 5(잔여), 이후 0 — 상한 50 초과 지급 없음
        assertThat(coinBalance(bot.getId()) - walletBefore).isEqualTo(5);
        assertThat(streakRepository.findByUserId(bot.getId())).isPresent();

        assertThat(report.missionsContributed()).isEqualTo(1);
        HouseMember botInHouse = houseMemberRepository.findByHouseIdAndUserId(withHuman.getId(), bot.getId()).orElseThrow();
        assertThat(dailyContributionRepository.existsByMissionIdAndMemberIdAndContributionDate(
                dueMission.getId(), botInHouse.getId(), today)).isTrue();
        HouseMember botInEmpty = houseMemberRepository.findByHouseIdAndUserId(withoutHuman.getId(), bot.getId()).orElseThrow();
        assertThat(dailyContributionRepository.existsByMissionIdAndMemberIdAndContributionDate(
                absentMission.getId(), botInEmpty.getId(), today)).isFalse();

        // 같은 틱을 다시 돌려도 중복 완료·중복 기여 없음
        BotTickReport again = botActivityService.runTick(at);
        assertThat(again.routinesCompleted()).isZero();
        assertThat(again.missionsContributed()).isZero();
        assertThat(again.failures()).isZero();
        assertThat(coinBalance(bot.getId()) - walletBefore).isEqualTo(5);

        // 활동 창 밖 시각이면 아무 것도 하지 않는다
        BotTickReport outside = botActivityService.runTick(today.atTime(LocalTime.of(3, 0)).atZone(KST));
        assertThat(outside.botsActed()).isZero();
        assertThat(outside.totalActions()).isZero();
    }

    @Test
    void 쉬는_날이면_루틴_완료와_미션_기여를_건너뛴다() {
        // 쉬는 날은 (봇, 날짜) 결정론이므로 과거 날짜 중 쉬는 날/쉬지 않는 날을 하나씩 잡아 같은 픽스처로 비교한다.
        // 쉬는 날엔 서비스 호출 자체가 없어 과거 날짜여도 부작용이 없고, 쉬지 않는 날의 완료는 과거 완료(보상 0)로 기록된다.
        User bot = bot("bot-latte"); // SPREAD
        BotActivityProfile profile = BotActivityProfile.SPREAD;
        int tick = lastActiveTick(profile);
        LocalDate restDay = null;
        LocalDate workDay = null;
        for (int d = 1; d <= 120 && (restDay == null || workDay == null); d++) {
            LocalDate date = LocalDate.now(KST).minusDays(d);
            if (BotDecision.isRestDay(bot.getId(), date)) {
                restDay = restDay == null ? date : restDay;
            } else {
                workDay = workDay == null ? date : workDay;
            }
        }
        assertThat(restDay).isNotNull();
        assertThat(workDay).isNotNull();
        User owner = human("rest-owner");
        House house = house(owner, 4);
        join(house, bot);
        Routine routine = routineDueAt(bot, profile, workDay, tick);
        missionUntil(house, mission -> true);

        BotTickReport rest = botActivityService.runTick(restDay.atTime(timeOfTick(tick)).atZone(KST));
        assertThat(rest.botsActed()).isEqualTo(1);
        assertThat(rest.routinesCompleted()).isZero();
        assertThat(rest.missionsContributed()).isZero();
        assertThat(rest.failures()).isZero();
        assertThat(routineLogRepository.findByRoutineIdAndRoutineDateAndStatus(
                routine.getId(), restDay, RoutineLogStatus.COMPLETED)).isEmpty();

        // 같은 봇·같은 루틴이라도 쉬지 않는 날엔 목표 틱이 지나면 완료한다(쉬는 날 스킵이 "루틴이 없어서"가 아님을 확인)
        BotTickReport work = botActivityService.runTick(workDay.atTime(timeOfTick(tick)).atZone(KST));
        assertThat(work.routinesCompleted()).isEqualTo(1);
        assertThat(routineLogRepository.findByRoutineIdAndRoutineDateAndStatus(
                routine.getId(), workDay, RoutineLogStatus.COMPLETED)).isPresent();
    }

    @Test
    void 응원은_완료_후_30_90분_창에서_같은_완료에_봇_전체_한_번_사람_기준_하루_2회까지만_보낸다() {
        LocalDate today = LocalDate.now(KST);
        User bot = bot("bot-latte"); // SPREAD 08–22
        User other = bot("bot-pine"); // SPREAD — 사람 기준 상한·같은 완료 1회가 봇 전체 합산인지 본다
        User owner = human("cheer-owner");
        House house = house(owner, 4);
        join(house, bot);
        Category category = categoryRepository.save(Category.create(owner, "사람 카테고리", "#FFFFFF", null, 0, PrivacyScope.PUBLIC));
        ZonedDateTime at1 = today.atTime(LocalTime.of(12, 0)).atZone(KST);
        completeRoutineAt(owner, category, "사람 루틴 1", today, at1.minusMinutes(85).toInstant()); // 85분 전 완료 → 지연(≤80) 경과

        BotTickReport first = botActivityService.runTick(at1);
        assertThat(first.cheersSent()).isEqualTo(1);
        assertThat(first.failures()).isZero();
        pinCheerCreatedAt(bot.getId(), owner.getId(), at1.toInstant()); // 시뮬레이션 시각과 맞춘다(실제 저장 시각은 벽시계)

        // 같은 완료로는 다시 보내지 않고, 90분이 지나면 창이 닫힌다
        assertThat(botActivityService.runTick(at1).cheersSent()).isZero();
        assertThat(botActivityService.runTick(at1.plusMinutes(10)).cheersSent()).isZero();
        assertThat(cheerCount(bot.getId(), owner.getId())).isEqualTo(1);

        // 다른 봇이 들어와도 "같은 완료"에는 봇 전체를 통틀어 한 번만 — 두 번째 봇은 보내지 않는다
        HouseMember otherMembership = join(house, other);
        assertThat(botActivityService.runTick(at1).cheersSent()).isZero();
        assertThat(cheerCount(other.getId(), owner.getId())).isZero();
        otherMembership.leave(); // 다음 단계는 봇 1명으로 진행(벽시계 created_at 이 섞이지 않게)
        houseMemberRepository.save(otherMembership);

        // 두 번째 완료(투두) → 두 번째 응원
        Todo todo = Todo.create(owner, category, "사람 투두", null, today, null);
        todo.complete(CurrencyType.COIN, 0, at1.plusMinutes(15).toInstant());
        todoRepository.save(todo);
        ZonedDateTime at2 = at1.plusMinutes(100); // 완료 후 85분
        assertThat(botActivityService.runTick(at2).cheersSent()).isEqualTo(1);
        pinCheerCreatedAt(bot.getId(), owner.getId(), at2.toInstant());
        assertThat(cheerCount(bot.getId(), owner.getId())).isEqualTo(2);

        // 세 번째 완료가 있어도 사람 기준 하루 2회 — 아직 한 번도 안 보낸 다른 봇도 막힌다
        otherMembership.reactivate();
        houseMemberRepository.save(otherMembership);
        completeRoutineAt(owner, category, "사람 루틴 2", today, at2.plusMinutes(15).toInstant());
        BotTickReport third = botActivityService.runTick(at2.plusMinutes(100));
        assertThat(third.cheersSent()).isZero();
        assertThat(cheerCount(bot.getId(), owner.getId())).isEqualTo(2);
        assertThat(cheerCount(other.getId(), owner.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM house_member_cheers WHERE sender_user_id = ? AND target_user_id = ? AND cheer_type IN ('great','support','best')",
                Long.class, bot.getId(), owner.getId())).isEqualTo(2L);
    }

    @Test
    void 방명록은_월요일에_집의_봇_한_명이_사람_방에_템플릿으로_한_번만_남기고_템플릿은_금칙어를_통과한다() {
        LocalDate monday = LocalDate.now(KST).with(DayOfWeek.MONDAY); // 이번 주 월요일(과거 또는 오늘)
        User bot = bot("bot-pine"); // SPREAD
        User owner = human("gb-owner");
        House house = house(owner, 4);
        join(house, bot);
        // 방 주인이 그날 완료한 루틴이 비공개 카테고리뿐이면 "{count}개" 문구에 새면 안 된다(집 구성원 열람 범위 HOUSE·PUBLIC 만)
        Category privateCategory = categoryRepository.save(Category.create(owner, "비공개", "#000000", null, 0, PrivacyScope.PRIVATE));
        completeRoutineAt(owner, privateCategory, "비밀 루틴", monday, monday.atTime(LocalTime.of(9, 0)).atZone(KST).toInstant());
        assertThat(botSocialActions.visibleCompletedCount(owner.getId(), monday)).isZero();
        ZonedDateTime at = monday.atTime(LocalTime.of(21, 50)).atZone(KST);

        BotTickReport report = botActivityService.runTick(at);

        assertThat(report.failures()).isZero();
        assertThat(report.guestbooksWritten()).isEqualTo(1);
        List<String> contents = jdbcTemplate.queryForList(
                "SELECT content FROM room_guestbooks WHERE house_id = ? AND author_id = ? AND room_owner_id = ?",
                String.class, house.getId(), bot.getId(), owner.getId());
        assertThat(contents).hasSize(1);
        assertThat(contents.get(0)).doesNotContain("{").doesNotContainPattern("\\d+개").isNotBlank();
        assertThat(contents.get(0).length()).isLessThanOrEqualTo(500);

        // 집 공개(HOUSE)·전체 공개 카테고리 완료만 개수에 잡힌다
        Category houseCategory = categoryRepository.save(Category.create(owner, "집 공개", "#000000", null, 1, PrivacyScope.HOUSE));
        completeRoutineAt(owner, houseCategory, "공개 루틴", monday, monday.atTime(LocalTime.of(10, 0)).atZone(KST).toInstant());
        assertThat(botSocialActions.visibleCompletedCount(owner.getId(), monday)).isEqualTo(1);

        // 같은 날 다시 돌려도 한 번만
        assertThat(botActivityService.runTick(at).guestbooksWritten()).isZero();
        assertThat(botActivityService.runTick(at.plusMinutes(9)).guestbooksWritten()).isZero();

        // 템플릿 30개 전부 금칙어 없음(닉네임·요일·개수 치환 후)
        for (int i = 0; i < 200; i++) {
            String rendered = BotGuestbookTemplates.render(new Random(i), "루미", DayOfWeek.MONDAY, i % 5);
            assertThat(bannedWordChecker.containsBannedWord(rendered)).as(rendered).isFalse();
        }
    }

    @Test
    void 거미줄은_같은_집_사람_방의_활성_거미줄을_확률_판정_틱에_청소하고_보상_3코인은_봇_지갑에_들어간다() {
        LocalDate today = LocalDate.now(KST);
        User bot = bot("bot-latte"); // SPREAD 08–22
        User owner = human("web-owner");
        House house = house(owner, 4);
        join(house, bot);
        personalRoomRepository.save(PersonalRoom.create(owner));
        Instant appearedAt = Instant.now().minusSeconds(3600);
        jdbcTemplate.update("""
                INSERT INTO room_cobwebs (room_user_id, appeared_at, cleaned_at, cleaned_by_user_id, updated_at)
                VALUES (?, ?, NULL, NULL, ?)
                """, owner.getId(), Timestamp.from(appearedAt), Timestamp.from(appearedAt));
        long walletBefore = coinBalance(bot.getId());

        int noCleanTick = BotDecision.activeTicks(BotActivityProfile.SPREAD).stream()
                .filter(t -> !BotDecision.shouldCleanCobweb(bot.getId(), today, t, owner.getId()))
                .findFirst().orElseThrow();
        assertThat(botActivityService.runTick(today.atTime(timeOfTick(noCleanTick)).atZone(KST)).cobwebsCleaned()).isZero();

        // 틱당 1%라 특정 날에는 청소 틱이 없을 수 있다 → 과거 날짜까지 훑어 청소로 판정되는 (날짜, 틱)을 잡는다.
        // 청소 자체는 날짜와 무관하게 실제 거미줄·지갑에 반영된다.
        ZonedDateTime cleanAt = cobwebCleanTick(bot.getId(), owner.getId(), today);
        BotTickReport report = botActivityService.runTick(cleanAt);

        assertThat(report.failures()).isZero();
        assertThat(report.cobwebsCleaned()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT cleaned_by_user_id FROM room_cobwebs WHERE room_user_id = ? AND cleaned_at IS NOT NULL",
                Long.class, owner.getId())).isEqualTo(bot.getId());
        assertThat(coinBalance(bot.getId()) - walletBefore).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_histories WHERE user_id = ? AND reason = 'COBWEB_CLEAN'", Long.class, bot.getId()))
                .isEqualTo(1L);
        // 청소된 뒤에는 더 청소하지 않는다
        assertThat(botActivityService.runTick(cleanAt).cobwebsCleaned()).isZero();
    }

    // 봇 활동 창(SPREAD) 안에서 shouldCleanCobweb 이 true 인 가장 최근 (날짜, 틱). 1%/틱이라 하루 84틱에 없을 수 있어 과거로 거슬러 간다.
    static ZonedDateTime cobwebCleanTick(long botId, long roomUserId, LocalDate from) {
        for (int d = 0; d < 400; d++) {
            LocalDate date = from.minusDays(d);
            for (int tick : BotDecision.activeTicks(BotActivityProfile.SPREAD)) {
                if (BotDecision.shouldCleanCobweb(botId, date, tick, roomUserId)) {
                    return date.atTime(timeOfTick(tick)).atZone(KST);
                }
            }
        }
        throw new AssertionError("거미줄 청소 틱을 찾지 못함");
    }

    @Test
    void 레이아웃은_월요일_활동_창_첫_틱에_이번_주_프리셋으로_바뀌고_revision_이_증가하며_그날_다시_바꾸지_않는다() {
        LocalDate monday = LocalDate.now(KST).with(DayOfWeek.MONDAY);
        User bot = bot("bot-pine"); // SPREAD
        User owner = human("layout-owner");
        House house = house(owner, 4);
        join(house, bot);
        List<Long> userItemIds = grantFurniture(bot, 8);
        roomCommandService.updateLayout(bot.getId(), new RoomLayoutUpdateRequest(
                0, List.of(), BotRoomPreset.first().placementsFor(userItemIds)));
        // "이번 주 아직 안 바꿈" 상태로 되돌린다
        jdbcTemplate.update("UPDATE personal_rooms SET updated_at = NOW() - INTERVAL 30 DAY WHERE user_id = ?", bot.getId());
        ZonedDateTime at = monday.atTime(LocalTime.of(8, 0)).atZone(KST);

        BotTickReport report = botActivityService.runTick(at);

        assertThat(report.failures()).isZero();
        assertThat(report.layoutsRotated()).isEqualTo(1);
        PersonalRoom room = personalRoomRepository.findById(bot.getId()).orElseThrow();
        assertThat(room.getLayoutRevision()).isEqualTo(2);
        BotRoomPreset expected = BotDecision.presetForWeek(monday);
        var placements = roomItemPlacementRepository.findByRoomUserIdWithItem(bot.getId());
        assertThat(placements).hasSize(8);
        BigDecimal firstX = expected.placementsFor(userItemIds).get(0).positionX();
        assertThat(placements.stream().map(p -> p.getPositionX().stripTrailingZeros()))
                .contains(firstX.stripTrailingZeros());

        // 같은 날 다음 틱: 이미 바뀐 방은 건너뛴다(revision 충돌 없음)
        assertThat(botActivityService.runTick(at.plusMinutes(10)).layoutsRotated()).isZero();
        assertThat(personalRoomRepository.findById(bot.getId()).orElseThrow().getLayoutRevision()).isEqualTo(2);
    }

    @Test
    void 한_봇의_행동이_거절돼도_다른_봇의_행동은_커밋된다() {
        LocalDate today = LocalDate.now(KST);
        User failing = botNotResting(today, profile -> true);
        BotActivityProfile failingProfile = profileOf(failing);
        User healthy = botNotResting(today, profile -> true); // 이미 만든 키는 건너뛰므로 다른 봇
        BotActivityProfile healthyProfile = profileOf(healthy);
        // 두 봇이 같은 틱에 활동하도록 활동 창 교집합의 마지막 틱을 고른다(교집합이 없으면 각자 마지막 틱에서 따로 돌린다)
        List<Integer> common = BotDecision.activeTicks(failingProfile).stream()
                .filter(BotDecision.activeTicks(healthyProfile)::contains).toList();
        int failingTick = common.isEmpty() ? lastActiveTick(failingProfile) : common.get(common.size() - 1);
        int healthyTick = common.isEmpty() ? lastActiveTick(healthyProfile) : failingTick;
        User owner = human("iso-owner");
        House house = house(owner, 6);
        join(house, failing);
        join(house, healthy);
        Routine failingRoutine = routineDueAt(failing, failingProfile, today, failingTick);
        Routine healthyRoutine = routineDueAt(healthy, healthyProfile, today, healthyTick);
        // 실패 유도: 실패 봇의 지갑을 없앤다 → 완료 트랜잭션이 WALLET_NOT_FOUND 로 롤백
        jdbcTemplate.update("DELETE FROM wallet_histories WHERE user_id = ?", failing.getId());
        jdbcTemplate.update("DELETE FROM user_wallets WHERE user_id = ?", failing.getId());

        BotTickReport report = botActivityService.runTick(today.atTime(timeOfTick(failingTick)).atZone(KST));
        if (healthyTick != failingTick) {
            BotTickReport second = botActivityService.runTick(today.atTime(timeOfTick(healthyTick)).atZone(KST));
            assertThat(second.routinesCompleted()).isEqualTo(1);
        } else {
            assertThat(report.routinesCompleted()).isEqualTo(1);
        }

        // 지갑 없음은 기존 서비스의 BusinessException(WALLET_NOT_FOUND) → 장애(failures)가 아니라 거절(businessSkips)로 집계
        assertThat(report.businessSkips()).isGreaterThanOrEqualTo(1);
        assertThat(report.failures()).isZero();
        assertThat(routineLogRepository.findByRoutineIdAndRoutineDateAndStatus(
                failingRoutine.getId(), today, RoutineLogStatus.COMPLETED)).isEmpty();
        assertThat(routineLogRepository.findByRoutineIdAndRoutineDateAndStatus(
                healthyRoutine.getId(), today, RoutineLogStatus.COMPLETED)).isPresent();
    }

    @Test
    void 봇_활성화_플래그가_꺼져_있으면_스케줄러_빈이_등록되지_않는다() {
        assertThat(applicationContext.getBeanNamesForType(BotActivityScheduler.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(BotActivityService.class)).hasSize(1);
    }

    // ---- fixtures ----

    private interface BotPredicate {
        boolean test(User candidate, BotActivityProfile profile);
    }

    // 카탈로그 키 순서로 봇을 만들며 조건을 만족하는 첫 봇을 돌려준다(없으면 null). 만든 봇은 전부 cleanup 대상.
    private User botUntil(LocalDate today, BotPredicate predicate) {
        for (BotProfile profile : BotProfileCatalog.PROFILES) {
            if (userRepository.findAllByBotTrueAndDeletedAtIsNull().stream()
                    .anyMatch(u -> profile.botKey().equals(u.getBotKey()))) {
                continue;
            }
            User candidate = bot(profile.botKey());
            if (predicate.test(candidate, profile.activity())) {
                return candidate;
            }
        }
        return null;
    }

    private User botNotResting(LocalDate today, Predicate<BotActivityProfile> profileFilter) {
        User bot = botUntil(today, (candidate, profile) ->
                profileFilter.test(profile) && !BotDecision.isRestDay(candidate.getId(), today));
        assertThat(bot).as("오늘 쉬지 않는 카탈로그 봇").isNotNull();
        return bot;
    }

    private BotActivityProfile profileOf(User bot) {
        return BotProfileCatalog.PROFILES.stream()
                .filter(p -> p.botKey().equals(bot.getBotKey())).findFirst().orElseThrow().activity();
    }

    private static int lastActiveTick(BotActivityProfile profile) {
        List<Integer> ticks = BotDecision.activeTicks(profile);
        return ticks.get(ticks.size() - 1);
    }

    private static LocalTime timeOfTick(int tick) {
        return LocalTime.ofSecondOfDay((long) tick * BotDecision.TICK_MINUTES * 60);
    }

    private Routine routineDueAt(User bot, BotActivityProfile profile, LocalDate today, int tick) {
        Category category = categoryRepository.save(Category.create(bot, "봇 카테고리", "#FFFFFF", null, 0, PrivacyScope.PUBLIC));
        for (int i = 0; i < 60; i++) {
            Routine routine = routineRepository.save(Routine.create(
                    bot, category, "봇 루틴 " + i, AuthType.CHECK, "DAILY", null, null, today.minusDays(1), null));
            routine.assignOriginToSelf();
            routineRepository.save(routine);
            if (BotDecision.isDue(BotDecision.routineCompletionTick(bot.getId(), today, routine.getId(), profile), tick)) {
                return routine;
            }
            routineRepository.delete(routine);
        }
        throw new AssertionError("목표 틱이 잡힌 루틴을 만들지 못함");
    }

    private HouseMission missionUntil(House house, Predicate<HouseMission> predicate) {
        Instant now = Instant.now();
        for (int i = 0; i < 60; i++) {
            HouseMission mission = houseMissionRepository.save(HouseMission.create(
                    house, "봇 미션 " + i, HouseMissionType.WEEKLY_MEMBER_COUNT, 10,
                    now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(6))));
            if (predicate.test(mission)) {
                return mission;
            }
            houseMissionRepository.delete(mission);
        }
        throw new AssertionError("조건에 맞는 미션을 만들지 못함");
    }

    private void completeRoutineAt(User user, Category category, String title, LocalDate date, Instant completedAt) {
        Routine routine = routineRepository.save(Routine.create(
                user, category, title, AuthType.CHECK, "DAILY", null, null, date.minusDays(1), null));
        routine.assignOriginToSelf();
        routineRepository.save(routine);
        routineLogRepository.save(RoutineLog.complete(routine, date, completedAt, CurrencyType.COIN, 0));
    }

    // 방금 저장된(가장 최근) 응원의 created_at 을 시뮬레이션 시각으로 맞춘다 — 서비스는 벽시계로 저장하므로
    // "이 완료 이후 이미 보냈는지" 판정을 시뮬레이션 시각 축에서 검증하기 위한 테스트 전용 보정.
    // JDBC Timestamp 는 세션 타임존 차이로 Hibernate 가 읽는 값과 어긋나므로(#309 에서 확인) 엔티티로 고친다.
    private void pinCheerCreatedAt(Long senderId, Long targetId, Instant at) {
        HouseMemberCheer latest = houseMemberCheerRepository
                .findBySender_BotTrueAndTarget_IdInAndCheerDate(List.of(targetId), LocalDate.now(KST)).stream()
                .filter(cheer -> cheer.getSender().getId().equals(senderId))
                .max(java.util.Comparator.comparingLong(HouseMemberCheer::getId)).orElseThrow();
        ReflectionTestUtils.setField(latest, "createdAt", at);
        houseMemberCheerRepository.save(latest);
    }

    private long cheerCount(Long senderId, Long targetId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM house_member_cheers WHERE sender_user_id = ? AND target_user_id = ?", Long.class, senderId, targetId);
    }

    private long coinBalance(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM user_wallets WHERE user_id = ? AND currency_type = 'COIN'", Long.class, userId);
    }

    private List<Long> grantFurniture(User bot, int count) {
        Theme theme = themeRepository.save(new Theme(MARKER, "활동 테스트 테마", "themes/" + MARKER + ".png", true));
        themeId = theme.getId();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Item item = itemRepository.save(new Item(theme, "furniture", "positioned", null, null,
                    "활동 테스트 가구 " + i, null, null, "items/" + MARKER + "/" + i + ".png", false, true));
            ids.add(userItemRepository.save(UserItem.create(bot, item)).getId());
        }
        return ids;
    }

    private User human(String prefix) {
        User user = userRepository.save(User.signUp(prefix + "-" + UUID.randomUUID() + "@rougether.dev"));
        userWalletRepository.saveAll(SignupWalletPolicy.issueAll(user));
        userIds.add(user.getId());
        return user;
    }

    private User bot(String botKey) {
        User user = userRepository.save(User.bot(botKey, "활동 테스트 " + botKey, null));
        userWalletRepository.saveAll(SignupWalletPolicy.issueAll(user));
        userIds.add(user.getId());
        return user;
    }

    private House house(User owner, int maxMembers) {
        House house = houseWithoutOwnerMembership(owner, maxMembers); // House.create 가 소유자 몫 1을 이미 센다
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        return house;
    }

    private House houseWithoutOwnerMembership(User owner, int maxMembers) {
        String code = ("BA" + UUID.randomUUID().toString().replace("-", "")).substring(0, 8).toUpperCase();
        House house = houseRepository.save(House.create(
                owner, "봇 활동 집", null, null, maxMembers, code, Instant.now().plus(Duration.ofDays(7))));
        houseIds.add(house.getId());
        return house;
    }

    private HouseMember join(House house, User bot) {
        HouseMember member = houseMemberRepository.save(HouseMember.create(house, bot, HouseMemberRole.MEMBER));
        house.increaseMemberCount();
        houseRepository.save(house);
        return member;
    }
}
