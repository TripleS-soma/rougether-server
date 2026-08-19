package com.triples.rougether.userapi.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.member.policy.SignupWalletPolicy;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import com.triples.rougether.userapi.bot.BotSeedRunner.SeedResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

// 동거 봇 시드의 실제 기동 경로(#307): 트랜잭션 밖에서 BotSeedRunner.seedAll() 을 호출해 봇마다 트랜잭션이 열리고
// 지갑·원장·루틴·가구·방이 실제로 커밋되는지 본다. 클래스 @Transactional 을 두면 MANDATORY 원장 기록이 테스트
// 트랜잭션에 묻혀 자기호출 버그를 못 잡으므로 일부러 두지 않고, 만든 데이터는 @AfterEach 에서 직접 지운다.
@SpringBootTest
class BotSeedCommitTest {

    private static final String MARKER = "bot_commit_test";

    @Autowired
    BotSeedRunner botSeedRunner;

    @Autowired
    CharacterRepository characterRepository;

    @Autowired
    ThemeRepository themeRepository;

    @Autowired
    ItemRepository itemRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final List<Long> characterIds = new ArrayList<>();
    private final List<Long> itemIds = new ArrayList<>();
    private Long themeId;

    @BeforeEach
    void seedMasterData() {
        cleanupBots();
        jdbcTemplate.update("INSERT INTO goals (code, name, sort_order, is_active) VALUES ('exercise', ?, 990, true)", MARKER);
        jdbcTemplate.update("INSERT INTO goals (code, name, sort_order, is_active) VALUES ('study', ?, 991, true)", MARKER);
        for (int i = 0; i < 2; i++) {
            characterIds.add(characterRepository.save(new Character(
                    MARKER + "_character_" + i, "커밋 테스트 캐릭터 " + i, "characters/" + MARKER + i + ".webp",
                    980 + i, true)).getId());
        }
        Theme theme = themeRepository.save(new Theme(MARKER, "커밋 테스트 테마", "themes/" + MARKER + ".png", true));
        themeId = theme.getId();
        for (int i = 0; i < 8; i++) {
            itemIds.add(itemRepository.save(new Item(theme, "furniture", "positioned", null, null,
                    "커밋 테스트 가구 " + i, null, null, "items/" + MARKER + "/" + i + ".png", false, true)).getId());
        }
    }

    @AfterEach
    void cleanup() {
        cleanupBots();
        jdbcTemplate.update("DELETE FROM items WHERE theme_id = ?", themeId);
        jdbcTemplate.update("DELETE FROM themes WHERE id = ?", themeId);
        for (Long id : characterIds) {
            jdbcTemplate.update("DELETE FROM characters WHERE id = ?", id);
        }
        jdbcTemplate.update("DELETE FROM goals WHERE name = ?", MARKER);
    }

    @Test
    void 트랜잭션_밖에서_돌리면_봇마다_지갑_원장_루틴_가구_방이_실제로_커밋된다() {
        SeedResult result = botSeedRunner.seedAll();

        assertThat(result).isEqualTo(new SeedResult(BotProfileCatalog.BOT_COUNT, 0, 0, 0));
        List<Long> botIds = jdbcTemplate.queryForList(
                "SELECT id FROM users WHERE is_bot = true AND deleted_at IS NULL", Long.class);
        assertThat(botIds).hasSize(BotProfileCatalog.BOT_COUNT);

        for (Long botId : botIds) {
            assertThat(count("SELECT balance FROM user_wallets WHERE user_id = ? AND currency_type = 'COIN'", botId))
                    .isEqualTo(SignupWalletPolicy.INITIAL_COIN_BALANCE);
            // 원장(#253)이 지갑과 함께 커밋돼야 한다 — 자기호출로 트랜잭션이 빠지면 여기서 0 이 된다.
            assertThat(count("SELECT COUNT(*) FROM wallet_histories WHERE user_id = ? AND reason = 'SIGNUP_BONUS'", botId))
                    .isGreaterThanOrEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM user_goals WHERE user_id = ?", botId)).isBetween(1, 2);
            assertThat(count("SELECT COUNT(*) FROM user_characters WHERE user_id = ? AND is_selected = true", botId))
                    .isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM routines WHERE user_id = ? AND deleted_at IS NULL", botId))
                    .isEqualTo(BotProfile.ROUTINE_COUNT);
            // origin 자기참조는 dirty checking 으로 커밋되는 값 — 트랜잭션이 없으면 유실된다.
            assertThat(count("SELECT COUNT(*) FROM routines WHERE user_id = ? AND origin_routine_id = id", botId))
                    .isEqualTo(BotProfile.ROUTINE_COUNT);
            assertThat(count("SELECT COUNT(*) FROM user_items WHERE user_id = ?", botId))
                    .isGreaterThanOrEqualTo(BotSeedService.FURNITURE_MIN);
            assertThat(count("SELECT layout_revision FROM personal_rooms WHERE user_id = ?", botId)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM room_item_placements WHERE room_user_id = ?", botId))
                    .isGreaterThanOrEqualTo(BotSeedService.FURNITURE_MIN);
        }

        // 두 번째 기동은 갱신만 — 커밋된 봇이 그대로 재사용된다.
        assertThat(botSeedRunner.seedAll()).isEqualTo(new SeedResult(0, BotProfileCatalog.BOT_COUNT, 0, 0));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE is_bot = true AND deleted_at IS NULL", Long.class))
                .isEqualTo((long) BotProfileCatalog.BOT_COUNT);
    }

    private int count(String sql, Long botId) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, botId);
        return value == null ? 0 : value;
    }

    // seed() 가 쓰는 테이블과 같이 갱신할 것 — 후속 이슈(#310)에서 봇이 routine_logs·streaks·room_guestbooks 등을
    // 쓰기 시작하면 여기도 FK 역순으로 추가해야 공용 Testcontainers DB 에 봇 행이 남지 않는다.
    private void cleanupBots() {
        List<Long> botIds = jdbcTemplate.queryForList("SELECT id FROM users WHERE is_bot = true", Long.class);
        for (Long botId : botIds) {
            jdbcTemplate.update("DELETE FROM room_item_placements WHERE room_user_id = ?", botId);
            jdbcTemplate.update("DELETE FROM room_surface_slots WHERE room_user_id = ?", botId);
            jdbcTemplate.update("DELETE FROM personal_rooms WHERE user_id = ?", botId);
            jdbcTemplate.update("DELETE FROM user_items WHERE user_id = ?", botId);
            jdbcTemplate.update("DELETE FROM routines WHERE user_id = ?", botId);
            jdbcTemplate.update("DELETE FROM categories WHERE user_id = ?", botId);
            jdbcTemplate.update("DELETE FROM user_characters WHERE user_id = ?", botId);
            jdbcTemplate.update("DELETE FROM user_goals WHERE user_id = ?", botId);
            jdbcTemplate.update("DELETE FROM wallet_histories WHERE user_id = ?", botId);
            jdbcTemplate.update("DELETE FROM user_wallets WHERE user_id = ?", botId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", botId);
        }
    }
}
