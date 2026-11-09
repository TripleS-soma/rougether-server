package com.triples.rougether.userapi.gacha;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.userapi.christmas.service.ChristmasEventPolicy;
import com.triples.rougether.userapi.gacha.service.ChristmasGachaCatalog;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

// 운영 DB 없이 환경별 ID가 다른 가상 카탈로그에 실제 V101 SQL을 실행함.
class ChristmasGachaSeedTest {

    private Connection connection;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws SQLException {
        connection = DriverManager.getConnection("jdbc:h2:mem:christmas-gacha-seed-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        jdbc.execute("""
                CREATE TABLE themes (
                    id BIGINT PRIMARY KEY, code VARCHAR(50) NOT NULL UNIQUE,
                    name VARCHAR(100) NOT NULL, is_active BOOLEAN NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE items (
                    id BIGINT PRIMARY KEY, theme_id BIGINT NOT NULL, asset_key VARCHAR(255) NOT NULL,
                    category_code VARCHAR(50) NOT NULL, placement_type VARCHAR(40) NOT NULL,
                    purchase_currency_type VARCHAR(30), price_amount INT, is_active BOOLEAN NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE gacha (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, code VARCHAR(50) NOT NULL UNIQUE,
                    name VARCHAR(120) NOT NULL, cost_currency_type VARCHAR(30), cost_amount INT NOT NULL,
                    draw_count INT NOT NULL, starts_at TIMESTAMP(0), ends_at TIMESTAMP(0),
                    is_active BOOLEAN NOT NULL, theme_id BIGINT,
                    created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE gacha_pool_entries (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, gacha_id BIGINT NOT NULL,
                    reward_type VARCHAR(30) NOT NULL, item_id BIGINT, rarity VARCHAR(30),
                    weight INT NOT NULL, is_active BOOLEAN NOT NULL
                )
                """);
    }

    @AfterEach
    void tearDown() throws SQLException {
        connection.close();
    }

    @Test
    void 승인된_10종만_비활성_이벤트에_중복없이_등록하고_기존_카탈로그와_일반_풀은_유지한다() {
        jdbc.update("INSERT INTO themes VALUES (777, 'snowy_christmas_202611', '겨울', FALSE)");
        jdbc.update("INSERT INTO themes VALUES (888, 'regular', '기존', TRUE)");
        List<String> keys = ChristmasGachaCatalog.ASSET_KEYS.stream().sorted().toList();
        for (int i = 0; i < keys.size(); i++) {
            insertItem(9000 + i * 17, 777, keys.get(i), 301 + i, false);
        }
        insertItem(19001, 777, "items/snowy-christmas-202611/furniture/unapproved.png", 777, false);
        insertItem(19002, 888, keys.getFirst(), 888, true);
        insertItem(19003, 888, "items/regular/furniture/chair.png", 999, true);
        jdbc.update("""
                INSERT INTO gacha (id, code, name, cost_currency_type, cost_amount, draw_count,
                    starts_at, ends_at, is_active, theme_id, created_at, updated_at)
                VALUES (111, 'furniture', '기존 가구', 'COIN', 25, 1, NULL, NULL,
                    TRUE, NULL, '2026-01-01 00:00:00', '2026-01-02 00:00:00')
                """);
        jdbc.update("""
                INSERT INTO gacha_pool_entries (gacha_id, reward_type, item_id, rarity, weight, is_active)
                VALUES (111, 'ITEM', 19003, '일반', 1, TRUE)
                """);
        var themesBefore = jdbc.queryForList("SELECT * FROM themes ORDER BY id");
        var itemsBefore = jdbc.queryForList("SELECT * FROM items ORDER BY id");
        var regularMachineBefore = jdbc.queryForList("SELECT * FROM gacha WHERE id = 111");
        var regularPoolBefore = jdbc.queryForList("SELECT * FROM gacha_pool_entries WHERE gacha_id = 111");

        migrate();
        migrate();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gacha", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gacha WHERE code = 'christmas_2026'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT is_active FROM gacha WHERE code = 'christmas_2026'", Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT cost_amount FROM gacha WHERE code = 'christmas_2026'", Integer.class))
                .isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT cost_currency_type FROM gacha WHERE code = 'christmas_2026'", String.class))
                .isEqualTo("COIN");
        assertThat(jdbc.queryForObject("SELECT draw_count FROM gacha WHERE code = 'christmas_2026'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT theme_id FROM gacha WHERE code = 'christmas_2026'", Long.class))
                .isEqualTo(777L);
        assertEventDates();

        var eventPool = jdbc.queryForList("""
                SELECT i.id, i.asset_key, p.rarity, p.weight, p.is_active, p.reward_type
                FROM gacha_pool_entries p JOIN gacha g ON g.id = p.gacha_id
                JOIN items i ON i.id = p.item_id WHERE g.code = 'christmas_2026' ORDER BY i.asset_key
                """);
        assertThat(eventPool).hasSize(10);
        assertThat(eventPool).extracting(row -> row.get("asset_key")).containsExactlyElementsOf(keys);
        assertThat(eventPool).allSatisfy(row -> {
            assertThat(((Number) row.get("id")).longValue()).isGreaterThanOrEqualTo(9000);
            assertThat(row.get("weight")).isEqualTo(1);
            assertThat(row.get("is_active")).isEqualTo(true);
            assertThat(row.get("reward_type")).isEqualTo("ITEM");
        });
        assertThat(eventPool).filteredOn(row -> "일반".equals(row.get("rarity"))).hasSize(5);
        assertThat(eventPool).filteredOn(row -> "희귀".equals(row.get("rarity"))).hasSize(3);
        assertThat(eventPool).filteredOn(row -> "전설".equals(row.get("rarity"))).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gacha_pool_entries", Integer.class)).isEqualTo(11);
        assertThat(jdbc.queryForList("SELECT * FROM themes ORDER BY id")).isEqualTo(themesBefore);
        assertThat(jdbc.queryForList("SELECT * FROM items ORDER BY id")).isEqualTo(itemsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM gacha WHERE id = 111")).isEqualTo(regularMachineBefore);
        assertThat(jdbc.queryForList("SELECT * FROM gacha_pool_entries WHERE gacha_id = 111")).isEqualTo(regularPoolBefore);
    }

    @Test
    void 카탈로그가_없는_환경에는_비활성_머신만_만들고_에셋을_추가하거나_자동_공개하지_않는다() {
        migrate();
        migrate();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gacha", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT is_active FROM gacha", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT theme_id FROM gacha", Long.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gacha_pool_entries", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM items", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM themes", Integer.class)).isZero();
        assertEventDates();
    }

    private void assertEventDates() {
        LocalDateTime startsAt = jdbc.queryForObject(
                "SELECT starts_at FROM gacha WHERE code = 'christmas_2026'", LocalDateTime.class);
        LocalDateTime endsAt = jdbc.queryForObject(
                "SELECT ends_at FROM gacha WHERE code = 'christmas_2026'", LocalDateTime.class);
        assertThat(startsAt).isEqualTo(LocalDateTime.of(2026, 11, 8, 15, 0));
        assertThat(endsAt).isEqualTo(LocalDateTime.of(2026, 12, 25, 15, 0));
        var policy = new ChristmasEventPolicy(true);
        assertThat(policy.isOpen(startsAt.toInstant(ZoneOffset.UTC).minusNanos(1))).isFalse();
        assertThat(policy.isOpen(startsAt.toInstant(ZoneOffset.UTC))).isTrue();
        assertThat(policy.isOpen(endsAt.toInstant(ZoneOffset.UTC).minusNanos(1))).isTrue();
        assertThat(policy.isOpen(endsAt.toInstant(ZoneOffset.UTC))).isFalse();
    }

    private void insertItem(long id, long themeId, String key, int price, boolean active) {
        boolean furniture = key.contains("/furniture/");
        jdbc.update("INSERT INTO items VALUES (?, ?, ?, ?, ?, 'COIN', ?, ?)", id, themeId, key,
                furniture ? "furniture" : key.contains("/wallpaper/") ? "wallpaper" : "floor",
                furniture ? "positioned" : "surface", price, active);
    }

    private void migrate() {
        ScriptUtils.executeSqlScript(connection,
                new ClassPathResource("db/migration/V101__seed_christmas_gacha.sql"));
    }
}
