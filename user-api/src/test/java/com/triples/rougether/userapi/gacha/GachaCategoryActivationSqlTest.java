package com.triples.rougether.userapi.gacha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.mysql.MySQLContainer;

// 운영용 SQL 원문을 MySQL 8에서 실행함. 전용 DB이므로 다른 통합 테스트의 seed를 변경하지 않음.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GachaCategoryActivationSqlTest {

    private static final String SHA = "a".repeat(40);
    // 오류 주입 트리거 생성만 허용하는 로컬 전용 설정임. 운영 DB 설정은 변경하지 않음.
    private final MySQLContainer database = new MySQLContainer("mysql:8.4")
            .withCommand("--log-bin-trust-function-creators=1");
    private Connection connection;
    private JdbcTemplate jdbc;
    private long wallpaperId;
    private long floorId;
    private long furnitureId;

    @BeforeAll
    void createSchemaAndInstallProcedures() throws Exception {
        database.start();
        connection = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        script("V1__init_schema.sql");
        script("V6__add_character_gacha_reward.sql");
        Path sql = Path.of("../deploy/sql/install_gacha_category_activation.sql");
        assertThat(sql).exists();
        String source = Files.readString(sql).replace("DELIMITER $$", "").replace("DELIMITER ;", "");
        for (String statement : source.split("\\$\\$")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement);
            }
        }
    }

    @AfterAll
    void closeDatabase() throws Exception {
        if (connection != null) {
            connection.close();
        }
        database.stop();
    }

    @BeforeEach
    void seedInactiveCategories() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_category_activation");
        jdbc.update("DELETE FROM gacha_pool_entries");
        jdbc.update("DELETE FROM gacha");
        jdbc.update("DELETE FROM items");
        jdbc.update("DELETE FROM themes");
        jdbc.update("INSERT INTO themes (id, code, name, is_active) VALUES (1, 'source', '소스', TRUE)");
        jdbc.update("""
                INSERT INTO gacha (id, code, name, cost_currency_type, cost_amount, draw_count, theme_id,
                                   is_active, created_at, updated_at)
                VALUES (10, 'legacy_theme', '기존 테마', 'COIN', 25, 1, 1,
                        TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        item(101, "wallpaper", "surface_slot", "wallpaper");
        item(102, "floor", "surface_slot", "floor");
        item(103, "rug", "positioned", null);
        for (int itemId : List.of(101, 102, 103)) {
            jdbc.update("""
                    INSERT INTO gacha_pool_entries (gacha_id, reward_type, item_id, rarity, weight, is_active)
                    VALUES (10, 'ITEM', ?, '일반', 1, TRUE)
                    """, itemId);
        }
        script("V66__consolidate_decor_gacha_categories.sql");
        wallpaperId = machineId("wallpaper_gacha");
        floorId = machineId("floor_gacha");
        furnitureId = machineId("furniture_gacha");
    }

    @Test
    void V66_직후에는_구서버_조회에도_기존_머신만_노출되고_검증후_세개만_활성화된다() {
        assertThat(jdbc.queryForList("SELECT code FROM gacha WHERE is_active = TRUE", String.class))
                .containsExactly("legacy_theme");
        List<Map<String, Object>> oldMachine = jdbc.queryForList("SELECT * FROM gacha WHERE id = 10");
        List<Map<String, Object>> oldPools = jdbc.queryForList("SELECT * FROM gacha_pool_entries ORDER BY id");

        activate();

        assertThat(activeCategoryCount()).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT * FROM gacha WHERE id = 10")).isEqualTo(oldMachine);
        assertThat(jdbc.queryForList("SELECT * FROM gacha_pool_entries ORDER BY id")).isEqualTo(oldPools);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "count", "zero_count", "sha", "missing_sha", "ready", "health", "drain"})
    void 운영_사전조건이_하나라도_불일치하면_세개_모두_비활성을_유지한다(String invalid) {
        List<Map<String, Object>> before = machines();
        assertThatThrownBy(() -> jdbc.execute("CALL activate_gacha_categories_v66(" +
                (invalid.equals("id") ? 10 : wallpaperId) + "," + floorId + "," + furnitureId + "," +
                (invalid.equals("count") ? 2 : invalid.equals("zero_count") ? 0 : 1) + ",1,1,'" + SHA + "'," +
                (invalid.equals("missing_sha") ? "NULL" : "'" + (invalid.equals("sha") ? "b".repeat(40) : SHA) + "'") + "," +
                (invalid.equals("ready") ? "NULL" : "TRUE") + "," +
                !invalid.equals("health") + "," + !invalid.equals("drain") + ")"))
                .hasMessageContaining("gacha category activation");
        assertThat(machines()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE gacha SET cost_amount = 26 WHERE code = 'floor_gacha'",
            "UPDATE gacha SET cost_currency_type = 'DIAMOND' WHERE code = 'floor_gacha'",
            "UPDATE gacha SET draw_count = 6 WHERE code = 'floor_gacha'",
            "UPDATE gacha SET theme_id = 1 WHERE code = 'floor_gacha'",
            "UPDATE gacha SET starts_at = CURRENT_TIMESTAMP WHERE code = 'floor_gacha'",
            "UPDATE gacha SET ends_at = CURRENT_TIMESTAMP WHERE code = 'floor_gacha'",
            "UPDATE gacha SET is_active = TRUE WHERE code = 'floor_gacha'",
            "UPDATE gacha SET code = 'FLOOR_GACHA' WHERE code = 'floor_gacha'",
            "UPDATE gacha SET cost_currency_type = 'coin' WHERE code = 'floor_gacha'",
            "UPDATE items SET surface_slot_type = 'background' WHERE id = 101",
            "UPDATE items SET placement_type = 'SURFACE_SLOT' WHERE id = 101",
            "UPDATE items SET surface_slot_type = 'floor' WHERE id = 103",
            "UPDATE items SET character_slot_type = 'head' WHERE id = 103",
            "UPDATE items SET category_code = 'character_accessory' WHERE id = 103",
            "UPDATE items SET is_active = FALSE WHERE id = 103",
            "UPDATE themes SET is_active = FALSE WHERE id = 1",
            "UPDATE gacha_pool_entries SET rarity = 'SSR' WHERE gacha_id <> 10 AND item_id = 103",
            "UPDATE gacha_pool_entries SET weight = 0 WHERE gacha_id <> 10 AND item_id = 103",
            "UPDATE gacha_pool_entries SET reward_type = 'CURRENCY' WHERE gacha_id <> 10 AND item_id = 103",
            "UPDATE gacha_pool_entries SET is_active = FALSE WHERE gacha_id <> 10 AND item_id = 103"
    })
    void 가격_기간_배치_활성_등급_보상이_변조되면_부분활성화하지_않는다(String mutation) {
        jdbc.update(mutation);
        List<Map<String, Object>> before = machines();

        assertThatThrownBy(this::activate).hasMessageContaining("gacha category activation");

        assertThat(machines()).isEqualTo(before);
    }

    @Test
    void 풀개수가_맞아도_동일_아이템_중복_등록은_거부한다() {
        jdbc.update("""
                INSERT INTO gacha_pool_entries (gacha_id, reward_type, item_id, rarity, weight, is_active)
                VALUES (?, 'ITEM', 103, '희귀', 1, TRUE)
                """, furnitureId);
        assertThatThrownBy(() -> jdbc.execute("CALL activate_gacha_categories_v66(" + wallpaperId + "," +
                floorId + "," + furnitureId + ",1,1,2,'" + SHA + "','" + SHA + "',TRUE,TRUE,TRUE)"))
                .hasMessageContaining("gacha category activation");
        assertThat(activeCategoryCount()).isZero();
    }

    @Test
    void 입력된_세_ID가_맞아도_정식코드_중복행이_있으면_활성화와_비활성화_모두_거부한다() {
        jdbc.update("""
                INSERT INTO gacha (code, name, cost_currency_type, cost_amount, draw_count,
                                   is_active, created_at, updated_at, theme_id)
                VALUES ('wallpaper_gacha', '중복', 'COIN', 25, 1,
                        FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL)
                """);
        List<Map<String, Object>> before = machines();

        assertThatThrownBy(this::activate).hasMessageContaining("expected exactly three canonical rows");
        assertThatThrownBy(this::deactivate).hasMessageContaining("expected exactly three canonical rows");

        assertThat(machines()).isEqualTo(before);
    }

    @Test
    void 활성화_UPDATE_도중_DB오류가_발생하면_이미_변경된_행과_시각도_롤백한다() {
        List<Map<String, Object>> before = machines();
        jdbc.execute("""
                CREATE TRIGGER fail_category_activation BEFORE UPDATE ON gacha FOR EACH ROW
                BEGIN
                  IF NEW.code = 'furniture_gacha' AND OLD.is_active = FALSE AND NEW.is_active = TRUE THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced activation failure';
                  END IF;
                END
                """);

        assertThatThrownBy(this::activate).hasMessageContaining("forced activation failure");

        assertThat(machines()).isEqualTo(before);
    }

    @Test
    void 구이미지_롤백전_세개만_내리고_잘못된_ID로는_아무것도_변경하지_않는다() {
        activate();
        List<Map<String, Object>> before = machines();
        assertThatThrownBy(() -> jdbc.execute("CALL deactivate_gacha_categories_v66(10," + floorId + "," + furnitureId + ")"))
                .hasMessageContaining("gacha category deactivation");
        assertThat(machines()).isEqualTo(before);

        deactivate();
        deactivate();

        assertThat(jdbc.queryForList("SELECT code FROM gacha WHERE is_active = TRUE", String.class))
                .containsExactly("legacy_theme");
    }

    private void activate() {
        jdbc.execute("CALL activate_gacha_categories_v66(" + wallpaperId + "," + floorId + "," + furnitureId +
                ",1,1,1,'" + SHA + "','" + SHA + "',TRUE,TRUE,TRUE)");
    }

    private void deactivate() {
        jdbc.execute("CALL deactivate_gacha_categories_v66(" + wallpaperId + "," + floorId + "," + furnitureId + ")");
    }

    private int activeCategoryCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM gacha WHERE id <> 10 AND is_active = TRUE", Integer.class);
    }

    private List<Map<String, Object>> machines() {
        return jdbc.queryForList("SELECT * FROM gacha ORDER BY id");
    }

    private long machineId(String code) {
        return jdbc.queryForObject("SELECT id FROM gacha WHERE code = ?", Long.class, code);
    }

    private void item(long id, String category, String placement, String surface) {
        jdbc.update("""
                INSERT INTO items (id, theme_id, name, category_code, placement_type, surface_slot_type,
                                   purchase_currency_type, price_amount, asset_key, is_limited, is_active)
                VALUES (?, 1, '테스트', ?, ?, ?, 'COIN', 25, 'test.png', FALSE, TRUE)
                """, id, category, placement, surface);
    }

    private void script(String name) {
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + name));
    }
}
