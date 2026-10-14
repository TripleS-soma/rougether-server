package com.triples.rougether.userapi.character;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

// V87(모루 비활성화·착용자 고양이 복귀)을 실제 MySQL 에 데이터를 심은 뒤 다시 실행해 검증함.
// Flyway 는 기동 시 빈 카탈로그에 이미 적용했으므로, 같은 스크립트를 한 연결에서 재실행함(임시 테이블이 연결 범위).
// 카탈로그 코드 'cat'·'moru'를 직접 만들므로 다른 테스트와 겹치지 않게 DB 를 분리함.
@SpringBootTest(properties = "spring.datasource.url=jdbc:tc:mysql:8.4:///moru_retirement")
class MoruRetirementMigrationTest {

    private static final String SCRIPT = "db/migration/V87__deactivate_moru_character.sql";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private CharacterRepository characterRepository;
    @Autowired private UserCharacterRepository userCharacterRepository;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> characterIds = new ArrayList<>();
    private Character cat;
    private Character moru;
    private Character bear;

    @BeforeEach
    void catalog() {
        cat = character("cat", true);
        moru = character("moru", true);
        bear = character("bear", true);
    }

    @AfterEach
    void clean() {
        for (Long id : userIds) {
            jdbc.update("DELETE FROM user_characters WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
        for (Long id : characterIds) {
            jdbc.update("DELETE FROM characters WHERE id = ?", id);
        }
    }

    @Test
    void 모루_착용자는_고양이로_돌아가고_고양이가_없으면_착용_상태로_지급한다() throws Exception {
        User noCat = user();
        own(noCat, moru, true);
        User hasCat = user();
        own(hasCat, moru, true);
        own(hasCat, cat, false);
        User ownsMoruWearsBear = user();
        own(ownsMoruWearsBear, moru, false);
        own(ownsMoruWearsBear, bear, true);
        User wearsCat = user();
        own(wearsCat, moru, false);
        own(wearsCat, cat, true);

        migrate();
        migrate();

        assertThat(jdbc.queryForObject("SELECT is_active FROM characters WHERE id = ?", Boolean.class, moru.getId()))
                .isFalse();
        assertThat(ownership(noCat)).containsExactlyInAnyOrder(
                Map.entry(moru.getId(), false), Map.entry(cat.getId(), true));
        assertThat(ownership(hasCat)).containsExactlyInAnyOrder(
                Map.entry(moru.getId(), false), Map.entry(cat.getId(), true));
        assertThat(ownership(ownsMoruWearsBear)).containsExactlyInAnyOrder(
                Map.entry(moru.getId(), false), Map.entry(bear.getId(), true));
        assertThat(ownership(wearsCat)).containsExactlyInAnyOrder(
                Map.entry(moru.getId(), false), Map.entry(cat.getId(), true));
        // 재실행해도 고양이 행이 늘지 않고 모루 보유 이력은 남음
        assertThat(rows(noCat)).isEqualTo(2);
        assertThat(rows(hasCat)).isEqualTo(2);
    }

    @Test
    void 고양이를_회수당한_착용자에게는_새_고양이_행을_만들어_착용한다() throws Exception {
        User user = user();
        own(user, moru, true);
        Long revoked = own(user, cat, false);
        jdbc.update("UPDATE user_characters SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", revoked);

        migrate();

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_characters
                WHERE user_id = ? AND character_id = ? AND is_selected = TRUE AND deleted_at IS NULL AND id <> ?
                """, Integer.class, user.getId(), cat.getId(), revoked)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT is_selected FROM user_characters WHERE id = ?", Boolean.class, revoked))
                .isFalse();
    }

    @Test
    void 활성_고양이_카탈로그가_없으면_착용은_두고_모루만_비활성화한다() throws Exception {
        jdbc.update("UPDATE characters SET is_active = FALSE WHERE id = ?", cat.getId());
        User user = user();
        own(user, moru, true);

        migrate();

        assertThat(ownership(user)).containsExactly(Map.entry(moru.getId(), true));
        assertThat(jdbc.queryForObject("SELECT is_active FROM characters WHERE id = ?", Boolean.class, moru.getId()))
                .isFalse();
    }

    private void migrate() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(SCRIPT));
        }
    }

    private Character character(String code, boolean active) {
        Character saved = characterRepository.save(new Character(code, code, "characters/" + code + ".png", 10, active));
        characterIds.add(saved.getId());
        return saved;
    }

    private User user() {
        User user = userRepository.save(User.signUp());
        userIds.add(user.getId());
        return user;
    }

    private Long own(User user, Character character, boolean selected) {
        return userCharacterRepository.save(UserCharacter.of(user, character, java.time.Instant.now(), selected)).getId();
    }

    private List<Map.Entry<Long, Boolean>> ownership(User user) {
        return jdbc.query("""
                SELECT character_id, is_selected FROM user_characters WHERE user_id = ? AND deleted_at IS NULL
                """, (rs, n) -> Map.entry(rs.getLong(1), rs.getBoolean(2)), user.getId());
    }

    private int rows(User user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_characters WHERE user_id = ?", Integer.class, user.getId());
    }
}
