package com.triples.rougether.domain.minigame;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class MinigameMigrationTest {

    private static final Instant STARTED_AT = Instant.parse("2026-09-12T00:00:00Z");

    private Connection connection;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws SQLException {
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:minigame-migration-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        script("V1__init_schema.sql");
        script("V75__add_minigames.sql");
        user(1);
        user(2);
    }

    @AfterEach
    void tearDown() throws SQLException {
        connection.close();
    }

    @Test
    void 새_플레이는_완료값_없이_저장되고_감사시각이_자동으로_채워진다() {
        run("run-start", 1, "room-runner");

        assertThat(jdbc.queryForMap("SELECT * FROM minigame_runs WHERE id = 'run-start'"))
                .containsEntry("user_id", 1L)
                .containsEntry("game_code", "room-runner")
                .containsEntry("rules_version", 1)
                .containsEntry("seed", -123)
                .containsEntry("ticks", null)
                .containsEntry("score", null)
                .containsEntry("finished_best_score", null)
                .containsEntry("personal_best", null)
                .containsEntry("finished_rank", null)
                .containsEntry("submission_hash", null)
                .containsEntry("finished_at", null);
        assertThat(jdbc.queryForObject("SELECT created_at FROM minigame_runs WHERE id = 'run-start'",
                Timestamp.class)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT updated_at FROM minigame_runs WHERE id = 'run-start'",
                Timestamp.class)).isNotNull();
    }

    @Test
    void 완료_결과와_최초_응답_스냅샷을_함께_저장한다() {
        run("run-finished", 1, "room-runner");

        jdbc.update("""
                UPDATE minigame_runs
                SET ticks = 600, score = 100, finished_best_score = 120, personal_best = FALSE,
                    finished_rank = 3, submission_hash = ?, finished_at = ?
                WHERE id = 'run-finished'
                """, "a".repeat(64), Timestamp.from(STARTED_AT.plusSeconds(10)));

        assertThat(jdbc.queryForMap("""
                SELECT ticks, score, finished_best_score, personal_best, finished_rank, submission_hash
                FROM minigame_runs WHERE id = 'run-finished'
                """))
                .containsEntry("ticks", 600)
                .containsEntry("score", 100)
                .containsEntry("finished_best_score", 120)
                .containsEntry("personal_best", false)
                .containsEntry("finished_rank", 3L)
                .containsEntry("submission_hash", "a".repeat(64));
    }

    @Test
    void 최고_점수는_사용자와_게임_조합별로_한_건만_허용한다() {
        bestScore(1, "room-runner", 10);
        bestScore(1, "tile-merge", 20);
        bestScore(2, "room-runner", 30);

        assertThatThrownBy(() -> bestScore(1, "room-runner", 40))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM minigame_best_scores", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT id FROM minigame_best_scores", Long.class)).doesNotHaveDuplicates();
        assertThat(jdbc.queryForList("SELECT created_at FROM minigame_best_scores", Timestamp.class))
                .doesNotContainNull();
    }

    @Test
    void 없는_사용자의_플레이와_최고_점수는_저장할_수_없다() {
        assertThatThrownBy(() -> run("orphan-run", 999, "room-runner"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> bestScore(999, "room-runner", 10))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 계정을_삭제하면_해당_사용자의_모든_게임_기록만_함께_삭제한다() {
        run("owner-run-1", 1, "room-runner");
        run("owner-run-2", 1, "tile-merge");
        run("other-run", 2, "room-runner");
        bestScore(1, "room-runner", 10);
        bestScore(1, "tile-merge", 20);
        bestScore(2, "room-runner", 30);

        jdbc.update("DELETE FROM users WHERE id = 1");

        assertThat(jdbc.queryForList("SELECT id FROM minigame_runs", String.class)).containsExactly("other-run");
        assertThat(jdbc.queryForList("SELECT user_id FROM minigame_best_scores", Long.class)).containsExactly(2L);
    }

    @Test
    void 완료_스냅샷을_부분적으로만_저장할_수_없다() {
        run("incomplete-run", 1, "room-runner");

        assertThatThrownBy(() -> jdbc.update("UPDATE minigame_runs SET score = 100 WHERE id = 'incomplete-run'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE minigame_runs SET finished_at = CURRENT_TIMESTAMP WHERE id = 'incomplete-run'
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 음수_최고점수와_시작_이전_만료를_거부한다() {
        assertThatThrownBy(() -> bestScore(1, "room-runner", -1))
                .isInstanceOf(DataIntegrityViolationException.class);
        run("expiry-run", 1, "room-runner");

        assertThatThrownBy(() -> jdbc.update("UPDATE minigame_runs SET expires_at = started_at WHERE id = 'expiry-run'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void user(long id) {
        jdbc.update("INSERT INTO users (id, created_at, updated_at) VALUES (?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", id);
    }

    private void run(String id, long userId, String gameCode) {
        jdbc.update("""
                INSERT INTO minigame_runs (id, user_id, game_code, rules_version, seed, started_at, expires_at)
                VALUES (?, ?, ?, 1, -123, ?, ?)
                """, id, userId, gameCode, Timestamp.from(STARTED_AT), Timestamp.from(STARTED_AT.plusSeconds(1800)));
    }

    private void bestScore(long userId, String gameCode, int score) {
        jdbc.update("""
                INSERT INTO minigame_best_scores (user_id, game_code, rules_version, score, achieved_at)
                VALUES (?, ?, 1, ?, ?)
                """, userId, gameCode, score, Timestamp.from(STARTED_AT));
    }

    private void script(String filename) {
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + filename));
    }
}
