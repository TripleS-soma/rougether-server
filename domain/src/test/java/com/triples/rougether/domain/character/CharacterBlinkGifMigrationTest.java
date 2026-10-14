package com.triples.rougether.domain.character;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

// V88: 고양이·모루를 뺀 7종의 대표 이미지를 blink.gif 로 바꾸는지 실제 migration SQL 로 검증함.
class CharacterBlinkGifMigrationTest {

    private Connection connection;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws SQLException {
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:character-blink-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        script("V1__init_schema.sql");
        String[] codes = {"cat", "dog", "tiger", "panda", "bear", "sheep", "horse", "otter", "moru"};
        for (int i = 0; i < codes.length; i++) {
            String before = codes[i].equals("cat") ? "characters/cat/animations/blink.gif" : "characters/" + codes[i] + ".png";
            jdbc.update("INSERT INTO characters (code, name, base_asset_key, sort_order, is_active) VALUES (?, ?, ?, ?, TRUE)",
                    codes[i], codes[i], before, i);
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        connection.close();
    }

    @Test
    void 고양이와_모루를_뺀_캐릭터의_대표_이미지를_blink_gif로_바꾸고_다시_실행해도_같다() {
        script("V88__character_blink_gif_base_asset.sql");
        script("V88__character_blink_gif_base_asset.sql");

        Map<String, String> keys = jdbc.queryForList("SELECT code, base_asset_key FROM characters").stream()
                .collect(Collectors.toMap(r -> (String) r.get("code"), r -> (String) r.get("base_asset_key")));
        for (String code : new String[] {"dog", "tiger", "panda", "bear", "sheep", "horse", "otter"}) {
            assertThat(keys.get(code)).isEqualTo("characters/" + code + "/animations/blink.gif");
        }
        assertThat(keys.get("cat")).isEqualTo("characters/cat/animations/blink.gif");
        assertThat(keys.get("moru")).isEqualTo("characters/moru.png"); // blink.gif 없음 → 유지
    }

    private void script(String filename) {
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + filename));
    }
}
