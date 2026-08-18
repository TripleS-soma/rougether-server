package com.triples.rougether.userapi.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.goal.entity.UserGoal;
import com.triples.rougether.domain.goal.repository.UserGoalRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.policy.SignupWalletPolicy;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.entity.RoomItemPlacement;
import com.triples.rougether.domain.room.repository.PersonalRoomRepository;
import com.triples.rougether.domain.room.repository.RoomItemPlacementRepository;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.bot.BotSeedRunner.SeedResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// 동거 봇 시드(#307) 내용 검증. 테스트 컨텍스트는 rougether.bots.enabled=false 라 기동 시드가 돌지 않으므로 러너를 직접
// 호출한다. 클래스 @Transactional 이라 러너 호출이 테스트 트랜잭션에 참여한다 — 실제 기동 경로(트랜잭션 밖, 봇마다 커밋)는
// BotSeedCommitTest 가 따로 검증한다.
@SpringBootTest
@Transactional
class BotSeederTest {

    @Autowired
    BotSeedRunner botSeedRunner;

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    UserRepository userRepository;

    @Autowired
    UserWalletRepository userWalletRepository;

    @Autowired
    UserGoalRepository userGoalRepository;

    @Autowired
    CharacterRepository characterRepository;

    @Autowired
    UserCharacterRepository userCharacterRepository;

    @Autowired
    RoutineRepository routineRepository;

    @Autowired
    ThemeRepository themeRepository;

    @Autowired
    ItemRepository itemRepository;

    @Autowired
    UserItemRepository userItemRepository;

    @Autowired
    PersonalRoomRepository personalRoomRepository;

    @Autowired
    RoomItemPlacementRepository roomItemPlacementRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void 카탈로그_6명을_지갑_목표_캐릭터_루틴_가구_방까지_한_번에_만든다() {
        seedMasterData();
        long onboardingCompletedBefore = userRepository.countOnboardingCompletedUsers();

        SeedResult result = botSeedRunner.seedAll();

        assertThat(result).isEqualTo(new SeedResult(BotProfileCatalog.BOT_COUNT, 0, 0, 0));
        List<User> bots = userRepository.findAllByBotTrueAndDeletedAtIsNull();
        assertThat(bots).hasSize(BotProfileCatalog.BOT_COUNT)
                .allSatisfy(bot -> {
                    assertThat(bot.isBot()).isTrue();
                    assertThat(bot.getBotKey()).startsWith("bot-");
                    assertThat(bot.getEmail()).isNull();
                });
        assertThat(bots).extracting(User::getNickname)
                .containsExactlyInAnyOrderElementsOf(
                        BotProfileCatalog.PROFILES.stream().map(BotProfile::nickname).toList());

        for (User bot : bots) {
            UserWallet coin = userWalletRepository.findByUserIdAndCurrencyType(bot.getId(), CurrencyType.COIN)
                    .orElseThrow();
            assertThat(coin.getBalance()).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE);

            List<UserGoal> goals = userGoalRepository.findByUserId(bot.getId());
            assertThat(goals).isNotEmpty().hasSizeLessThanOrEqualTo(2);
            assertThat(goals.stream().filter(UserGoal::isPrimary).count()).isEqualTo(1);

            List<UserCharacter> characters = userCharacterRepository.findByUserIdAndDeletedAtIsNull(bot.getId());
            assertThat(characters).hasSize(1);
            assertThat(characters.get(0).isSelected()).isTrue();

            List<Routine> routines = routineRepository
                    .findByUserIdAndDeletedAtIsNullOrderByScheduledTimeAscOriginRoutineIdAsc(bot.getId());
            assertThat(routines).hasSize(BotProfile.ROUTINE_COUNT)
                    .allSatisfy(routine -> {
                        assertThat(routine.getRepeatType()).isEqualTo("DAILY");
                        assertThat(routine.getOriginRoutineId()).isEqualTo(routine.getId());
                        assertThat(routine.getCategory()).isNotNull();
                    });

            List<UserItem> items = userItemRepository.findByUserIdAndDeletedAtIsNull(bot.getId());
            assertThat(items).hasSizeGreaterThanOrEqualTo(BotSeedService.FURNITURE_MIN)
                    .allSatisfy(item -> assertThat(item.getItem().getPlacementType()).isEqualTo("positioned"));

            PersonalRoom room = personalRoomRepository.findById(bot.getId()).orElseThrow();
            assertThat(room.isFreeLayout()).isTrue();
            assertThat(room.getLayoutRevision()).isEqualTo(1);
            List<RoomItemPlacement> placements = roomItemPlacementRepository.findByRoomUserIdWithItem(bot.getId());
            assertThat(placements).hasSize(Math.min(items.size(), BotRoomPreset.SLOT_COUNT));
        }

        // 봇도 온보딩 완료 계약(목표 1개 + 대표 캐릭터)을 충족하지만, 관측 카운트(#308)에서는 제외된다.
        assertThat(userRepository.countOnboardingCompletedUsers()).isEqualTo(onboardingCompletedBefore);
        assertThat(userRepository.findOnboardingCompletedUserIds(bots.stream().map(User::getId).toList()))
                .hasSize(BotProfileCatalog.BOT_COUNT);
    }

    @Test
    void 두_번_실행해도_중복_생성_없이_갱신만_하고_바뀐_닉네임을_카탈로그_값으로_되돌린다() {
        seedMasterData();
        botSeedRunner.seedAll();
        BotProfile first = BotProfileCatalog.PROFILES.get(0);
        User bot = userRepository.findByBotKey(first.botKey()).orElseThrow();
        bot.changeNickname("운영자가 바꾼 이름");
        bot.changeBio("임시 소개");
        int itemsBefore = userItemRepository.findByUserIdAndDeletedAtIsNull(bot.getId()).size();
        int placementsBefore = roomItemPlacementRepository.findByRoomUserIdWithItem(bot.getId()).size();

        SeedResult second = botSeedRunner.seedAll();

        assertThat(second).isEqualTo(new SeedResult(0, BotProfileCatalog.BOT_COUNT, 0, 0));
        assertThat(userRepository.findAllByBotTrueAndDeletedAtIsNull()).hasSize(BotProfileCatalog.BOT_COUNT);
        User refreshed = userRepository.findByBotKey(first.botKey()).orElseThrow();
        assertThat(refreshed.getNickname()).isEqualTo(first.nickname());
        assertThat(refreshed.getBio()).isEqualTo(first.bio());
        // 갱신 경로는 지갑·루틴·가구·방을 다시 만들지 않는다.
        assertThat(userItemRepository.findByUserIdAndDeletedAtIsNull(bot.getId())).hasSize(itemsBefore);
        assertThat(roomItemPlacementRepository.findByRoomUserIdWithItem(bot.getId())).hasSize(placementsBefore);
        assertThat(routineRepository
                .findByUserIdAndDeletedAtIsNullOrderByScheduledTimeAscOriginRoutineIdAsc(bot.getId()))
                .hasSize(BotProfile.ROUTINE_COUNT);
        assertThat(userWalletRepository.findByUserIdAndCurrencyType(bot.getId(), CurrencyType.COIN)
                .orElseThrow().getBalance()).isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE);
    }

    @Test
    void 목표_캐릭터_가구_중_하나라도_비어_있으면_봇을_만들지_않고_건너뛴다() {
        // 반쪽 봇(지갑만 있고 캐릭터·가구 없음)을 남기지 않는 계약. 공유 DB 잔존 데이터를 트랜잭션 안에서 비활성화해
        // 마스터 데이터 미비 상태를 재현한다(롤백으로 원복).
        jdbcTemplate.update("UPDATE characters SET is_active = false");

        SeedResult result = botSeedRunner.seedAll();

        assertThat(result).isEqualTo(new SeedResult(0, 0, BotProfileCatalog.BOT_COUNT, 0));
        assertThat(userRepository.findAllByBotTrueAndDeletedAtIsNull()).isEmpty();
    }

    @Test
    void 마스터_데이터가_채워진_뒤_다시_기동하면_건너뛴_봇이_온전히_만들어진다() {
        jdbcTemplate.update("UPDATE characters SET is_active = false");
        assertThat(botSeedRunner.seedAll().created()).isZero();

        seedMasterData();
        SeedResult second = botSeedRunner.seedAll();

        assertThat(second).isEqualTo(new SeedResult(BotProfileCatalog.BOT_COUNT, 0, 0, 0));
        for (User bot : userRepository.findAllByBotTrueAndDeletedAtIsNull()) {
            assertThat(userCharacterRepository.findByUserIdAndDeletedAtIsNull(bot.getId())).hasSize(1);
            assertThat(userItemRepository.findByUserIdAndDeletedAtIsNull(bot.getId()))
                    .hasSizeGreaterThanOrEqualTo(BotSeedService.FURNITURE_MIN);
        }
    }

    @Test
    void soft_delete_된_봇은_되살리지_않고_건너뛴다() {
        seedMasterData();
        botSeedRunner.seedAll();
        User bot = userRepository.findByBotKey(BotProfileCatalog.PROFILES.get(0).botKey()).orElseThrow();
        bot.softDelete(java.time.Instant.now());

        SeedResult result = botSeedRunner.seedAll();

        assertThat(result).isEqualTo(new SeedResult(0, BotProfileCatalog.BOT_COUNT - 1, 1, 0));
        assertThat(userRepository.findAllByBotTrueAndDeletedAtIsNull()).hasSize(BotProfileCatalog.BOT_COUNT - 1);
    }

    @Test
    void 비활성_아이템과_surface_slot_아이템은_봇에게_지급하지_않는다() {
        seedMasterData();
        Theme theme = themeRepository.save(new Theme("bot_seed_filter", "봇 시드 필터", "themes/bot.png", true));
        itemRepository.save(new Item(theme, "furniture", "positioned", null, null, "쓸 수 있는 의자",
                null, null, "items/bot/chair.png", false, true));
        itemRepository.save(new Item(theme, "furniture", "positioned", null, null, "판매 중지 책상",
                null, null, "items/bot/desk-off.png", false, false));
        itemRepository.save(new Item(theme, "wallpaper", "surface_slot", "wallpaper", null, "벽지",
                null, null, "items/bot/wall.png", false, true));

        botSeedRunner.seedAll();

        for (User bot : userRepository.findAllByBotTrueAndDeletedAtIsNull()) {
            List<UserItem> items = userItemRepository.findByUserIdAndDeletedAtIsNull(bot.getId());
            assertThat(items).allSatisfy(userItem -> {
                Item item = userItem.getItem();
                assertThat(item.isActive()).isTrue();
                assertThat(item.getTheme().isActive()).isTrue();
                assertThat(item.getPlacementType()).isEqualTo("positioned");
            });
            assertThat(items).extracting(userItem -> userItem.getItem().getName())
                    .doesNotContain("판매 중지 책상", "벽지");
        }
    }

    @Test
    void 프로필의_대표_캐릭터_코드가_활성이면_그_캐릭터를_없으면_순번_캐릭터를_지급한다() {
        // 카탈로그 첫 봇(bot-dawn)의 코드만 활성 캐릭터로 넣고, 나머지 봇은 순번 대체를 타게 한다.
        seedMasterData();
        String dawnCode = BotProfileCatalog.PROFILES.get(0).characterCode();
        Character dawnCharacter = characterRepository.save(new Character(
                dawnCode, "새벽이 전용", "characters/bot-dawn.webp", 950, true));
        Character fallback = characterRepository.save(new Character(
                "bot_seed_fallback", "대체 캐릭터", "characters/bot-fallback.webp", 951, true));

        botSeedRunner.seedAll();

        User dawn = userRepository.findByBotKey("bot-dawn").orElseThrow();
        assertThat(userCharacterRepository.findByUserIdAndDeletedAtIsNull(dawn.getId()))
                .singleElement()
                .satisfies(userCharacter -> assertThat(userCharacter.getCharacter().getId())
                        .isEqualTo(dawnCharacter.getId()));
        // 대체 경로: 코드가 없는 봇은 활성 캐릭터 중 하나를 반드시 받는다(빈 손 없음).
        for (User bot : userRepository.findAllByBotTrueAndDeletedAtIsNull()) {
            assertThat(userCharacterRepository.findByUserIdAndDeletedAtIsNull(bot.getId()))
                    .singleElement()
                    .satisfies(userCharacter -> assertThat(userCharacter.isSelected()).isTrue());
        }
        assertThat(fallback.getId()).isNotNull();
    }

    @Test
    void 테스트_컨텍스트에서는_기동_시드_컴포넌트가_등록되지_않는다() {
        assertThat(applicationContext.getBeanNamesForType(BotSeeder.class)).isEmpty();
    }

    private void seedMasterData() {
        for (String[] goal : new String[][] {
                {"exercise", "운동"}, {"study", "공부"}, {"sleep", "수면"}, {"reading", "독서"},
                {"organize", "정리"}, {"job", "취업 준비"}, {"habit", "생활 습관"}}) {
            jdbcTemplate.update(
                    "INSERT INTO goals (code, name, sort_order, is_active) VALUES (?, ?, 900, true)",
                    goal[0], goal[1]);
        }
        for (int i = 0; i < 3; i++) {
            characterRepository.save(new Character(
                    "bot_seed_character_" + i, "봇 캐릭터 " + i, "characters/bot-" + i + ".webp", 900 + i, true));
        }
        Theme theme = themeRepository.save(new Theme("bot_seed_theme", "봇 시드 테마", "themes/bot-seed.png", true));
        for (int i = 0; i < 10; i++) {
            itemRepository.save(new Item(theme, i % 2 == 0 ? "furniture" : "prop", "positioned", null, null,
                    "봇 가구 " + i, null, null, "items/bot-seed/" + i + ".png", false, true));
        }
    }
}
