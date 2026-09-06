package com.triples.rougether.domain.gacha;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class GachaCategoryMigrationTest {

    private Connection connection;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws SQLException {
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:gacha-category-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        // 복제 DDL 대신 실제 테이블 생성/캐릭터 보상 migration 위에서 변환 SQL을 실행함.
        script("V1__init_schema.sql");
        script("V6__add_character_gacha_reward.sql");
        theme(1, true);
        theme(2, true);
        theme(3, false);
    }

    @AfterEach
    void tearDown() throws SQLException {
        connection.close();
    }

    @Test
    void 비어있는_DB에도_테마_공통_25코인_3종을_비활성으로_한번씩_준비한다() {
        migrate();
        migrate();

        assertThat(jdbc.queryForList("""
                SELECT code, name, cost_currency_type, cost_amount, draw_count,
                       theme_id, starts_at, ends_at, is_active
                FROM gacha ORDER BY code
                """))
                .hasSize(3)
                .allSatisfy(row -> {
                    assertThat(row.get("cost_currency_type")).isEqualTo("COIN");
                    assertThat(row.get("cost_amount")).isEqualTo(25);
                    assertThat(row.get("draw_count")).isEqualTo(1);
                    assertThat(row.get("theme_id")).isNull();
                    assertThat(row.get("starts_at")).isNull();
                    assertThat(row.get("ends_at")).isNull();
                    assertThat(row.get("is_active")).isEqualTo(false);
                })
                .extracting(row -> row.get("code"))
                .containsExactly("floor_gacha", "furniture_gacha", "wallpaper_gacha");
    }

    @Test
    void 테마를_넘어_실제_배치유형별로_통합하고_중복은_최상위_등급을_보존한다() {
        machine(10, "forest", 1L, true);
        machine(20, "hanok", 2L, true);
        item(101, 1, "wallpaper", "surface_slot", "wallpaper", null, true);
        item(102, 2, "floor", "surface_slot", "floor", null, true);
        item(103, 2, "rug", "positioned", null, null, true);
        item(104, 1, "furniture", "positioned", null, null, true);
        entry(1001, 10, 101, "일반", true);
        entry(1002, 20, 101, "희귀", true);
        entry(1003, 20, 102, "희귀", true);
        entry(1004, 10, 103, "일반", true);
        entry(1005, 10, 104, "희귀", true);
        entry(1006, 20, 104, "전설", true);
        entry(1007, 20, 104, "일반", true);

        migrate();

        assertThat(categoryRewards()).containsExactly(
                new Reward("floor_gacha", 102, "희귀", 1, true),
                new Reward("furniture_gacha", 103, "일반", 1, true),
                new Reward("furniture_gacha", 104, "전설", 1, true),
                new Reward("wallpaper_gacha", 101, "희귀", 1, true));
        assertThat(machineActive(10)).isTrue();
        assertThat(machineActive(20)).isTrue();
    }

    @Test
    void 아이템과_테마_재활성화용_등록은_보존하지만_내려간_풀과_한정_머신은_편입하지_않는다() {
        machine(10, "active", 1L, true);
        machine(20, "retired", 1L, false);
        machine(30, "scheduled", 1L, true);
        machine(40, "expired", 1L, true);
        machine(50, "ongoing-limited", 1L, true);
        jdbc.update("UPDATE gacha SET starts_at = ? WHERE id = 30",
                Timestamp.from(Instant.now().plusSeconds(86400)));
        jdbc.update("UPDATE gacha SET ends_at = ? WHERE id = 40",
                Timestamp.from(Instant.now().minusSeconds(86400)));
        jdbc.update("UPDATE gacha SET starts_at = ?, ends_at = ? WHERE id = 50",
                Timestamp.from(Instant.now().minusSeconds(86400)),
                Timestamp.from(Instant.now().plusSeconds(86400)));
        for (int itemId = 101; itemId <= 108; itemId++) {
            item(itemId, itemId == 103 ? 3 : 1,
                    "furniture", "positioned", null, null, itemId != 102);
        }
        entry(1001, 10, 101, "일반", true);
        entry(1002, 10, 102, "희귀", true);
        entry(1003, 10, 103, "전설", true);
        entry(1004, 20, 104, "전설", true);
        entry(1005, 30, 105, "전설", true);
        entry(1006, 40, 106, "전설", true);
        entry(1007, 10, 107, "전설", false);
        entry(1008, 50, 108, "전설", true);

        migrate();

        assertThat(categoryRewards()).extracting(Reward::itemId).containsExactly(101L, 102L, 103L);
        assertThat(machineActive(20)).isFalse();
        assertThat(machineActive(30)).isTrue();
        assertThat(machineActive(40)).isTrue();
        assertThat(machineActive(50)).isTrue();
        assertThat(jdbc.queryForObject("SELECT is_active FROM items WHERE id = 102", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT is_active FROM themes WHERE id = 3", Boolean.class)).isFalse();
    }

    @Test
    void 배경과_악세사리와_상충하는_배치필드는_어느_통합_풀에도_들어가지_않는다() {
        machine(10, "malformed", 1L, true);
        item(101, 1, "background", "surface_slot", "wallpaper", null, true);
        item(102, 1, "background", "positioned", null, null, true);
        item(103, 1, "character_accessory", "positioned", null, null, true);
        item(104, 1, "character_accessory", "surface_slot", "floor", null, true);
        item(105, 1, "furniture", "positioned", "floor", null, true);
        item(106, 1, "wallpaper", "surface_slot", null, null, true);
        item(107, 1, "wallpaper", "character", "wallpaper", null, true);
        item(108, 1, "wallpaper", "surface_slot", "wallpaper", "head", true);
        item(109, 1, "floor", "surface_slot", "floor", "head", true);
        item(110, 1, "furniture", "positioned", null, "head", true);
        item(111, 1, "background", "surface_slot", "background", null, true);
        for (int itemId = 101; itemId <= 111; itemId++) {
            entry(900 + itemId, 10, itemId, "전설", true);
        }

        migrate();

        assertThat(categoryRewards()).isEmpty();
        assertThat(machineActive(10)).isTrue();
    }

    @Test
    void 누락되거나_지원하지_않는_등급만_있는_아이템은_일반으로_정규화한다() {
        machine(10, "legacy-rarity", 1L, true);
        item(101, 1, "furniture", "positioned", null, null, true);
        item(102, 1, "furniture", "positioned", null, null, true);
        item(103, 1, "furniture", "positioned", null, null, true);
        entry(1001, 10, 101, null, true);
        entry(1002, 10, 102, "SSR", true);
        entry(1003, 10, 103, "SSR", true);
        entry(1004, 10, 103, "희귀", true);

        migrate();

        assertThat(categoryRewards()).extracting(Reward::rarity).containsExactly("일반", "일반", "희귀");
    }

    @Test
    void 캐릭터_악세사리_머신과_혼합_머신은_보존하고_기존_풀_ID와_내용도_그대로_둔다() {
        machine(10, "decor", 1L, true);
        machine(20, "theme1_accessories", 1L, true);
        machine(30, "characters", null, true);
        machine(40, "mixed", 1L, true);
        item(101, 1, "furniture", "positioned", null, null, true);
        item(102, 1, "character_accessory", "character", null, "head", true);
        entry(1001, 10, 101, "전설", true);
        entry(1002, 20, 102, null, true);
        entry(1003, 40, 101, "희귀", true);
        entry(1004, 40, 102, null, true);
        jdbc.update("""
                INSERT INTO characters (id, code, name, base_asset_key, sort_order, is_active)
                VALUES (1, 'cat', '고양이', 'characters/cat.png', 1, TRUE)
                """);
        jdbc.update("""
                INSERT INTO gacha_pool_entries (id, gacha_id, reward_type, character_id, weight, is_active)
                VALUES (1005, 30, 'CHARACTER', 1, 1, TRUE)
                """);
        List<Map<String, Object>> originalRows = jdbc.queryForList("SELECT * FROM gacha_pool_entries ORDER BY id");
        List<Map<String, Object>> originalMachines = jdbc.queryForList("SELECT * FROM gacha ORDER BY id");

        migrate();

        assertThat(machineActive(10)).isTrue();
        assertThat(machineActive(20)).isTrue();
        assertThat(machineActive(30)).isTrue();
        assertThat(machineActive(40)).isTrue();
        assertThat(categoryRewards()).containsExactly(new Reward("furniture_gacha", 101, "전설", 1, true));
        assertThat(jdbc.queryForList("SELECT * FROM gacha_pool_entries WHERE id <= 1005 ORDER BY id"))
                .isEqualTo(originalRows);
        assertThat(jdbc.queryForList("SELECT * FROM gacha WHERE id <= 40 ORDER BY id"))
                .isEqualTo(originalMachines);
    }

    @Test
    void 사전_활성화된_정식_머신도_ID를_보존하며_비활성_준비_상태로_맞춘다() {
        machine(10, "wallpaper_gacha", 1L, true);
        jdbc.update("UPDATE gacha SET cost_currency_type = 'DIAMOND', cost_amount = 100, draw_count = 6 WHERE id = 10");
        machine(20, "legacy", 1L, true);
        item(101, 1, "wallpaper", "surface_slot", "wallpaper", null, true);
        entry(1001, 10, 101, "희귀", true);
        entry(1002, 20, 101, "전설", true);

        migrate();
        migrate();

        assertThat(jdbc.queryForList("SELECT id FROM gacha WHERE code = 'wallpaper_gacha'", Long.class))
                .containsExactly(10L);
        assertThat(jdbc.queryForList("SELECT id FROM gacha_pool_entries WHERE gacha_id = 10", Long.class))
                .containsExactly(1001L);
        assertThat(categoryRewards()).containsExactly(new Reward("wallpaper_gacha", 101, "희귀", 1, true));
        assertThat(jdbc.queryForMap("SELECT cost_amount, draw_count, theme_id, is_active FROM gacha WHERE id = 10"))
                .containsEntry("cost_amount", 25)
                .containsEntry("draw_count", 1)
                .containsEntry("theme_id", null)
                .containsEntry("is_active", false);
    }

    private void theme(long id, boolean active) {
        jdbc.update("INSERT INTO themes (id, code, name, is_active) VALUES (?, ?, ?, ?)",
                id, "theme" + id, "테마 " + id, active);
    }

    private void machine(long id, String code, Long themeId, boolean active) {
        jdbc.update("""
                INSERT INTO gacha (id, code, name, cost_currency_type, cost_amount, draw_count,
                                   is_active, created_at, updated_at, theme_id)
                VALUES (?, ?, ?, 'COIN', 25, 1, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)
                """, id, code, code, active, themeId);
    }

    private void item(long id, long themeId, String category, String placement, String surface,
                      String characterSlot, boolean active) {
        jdbc.update("""
                INSERT INTO items (id, theme_id, category_code, placement_type, surface_slot_type,
                                   character_slot_type, name, asset_key, is_limited, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?)
                """, id, themeId, category, placement, surface, characterSlot,
                "아이템 " + id, "items/" + id + ".png", active);
    }

    private void entry(long id, long machineId, long itemId, String rarity, boolean active) {
        jdbc.update("""
                INSERT INTO gacha_pool_entries (id, gacha_id, reward_type, item_id, rarity, weight, is_active)
                VALUES (?, ?, 'ITEM', ?, ?, 1, ?)
                """, id, machineId, itemId, rarity, active);
    }

    private boolean machineActive(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT is_active FROM gacha WHERE id = ?", Boolean.class, id));
    }

    private List<Reward> categoryRewards() {
        return jdbc.query("""
                SELECT g.code, e.item_id, e.rarity, e.weight, e.is_active
                FROM gacha_pool_entries e JOIN gacha g ON g.id = e.gacha_id
                WHERE g.code IN ('wallpaper_gacha', 'floor_gacha', 'furniture_gacha')
                ORDER BY g.code, e.item_id
                """, (row, rowNumber) -> new Reward(row.getString(1), row.getLong(2), row.getString(3),
                row.getInt(4), row.getBoolean(5)));
    }

    private void migrate() {
        script("V66__consolidate_decor_gacha_categories.sql");
    }

    private void script(String filename) {
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + filename));
    }

    private record Reward(String code, long itemId, String rarity, int weight, boolean active) {
    }
}
