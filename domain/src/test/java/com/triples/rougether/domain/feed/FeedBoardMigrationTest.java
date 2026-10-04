package com.triples.rougether.domain.feed;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class FeedBoardMigrationTest {
    @Test
    void 기존글과_구버전등록은_인증게시판으로_분류하고_원래_hash를_보존한다() throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:feed-board-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V1__init_schema.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V79__add_feed.sql"));
            jdbc.update("insert into users (id, created_at, updated_at) values (1, current_timestamp, current_timestamp)");
            String insert = "insert into feed_posts (author_id, client_post_id, request_hash, content, created_at, updated_at) "
                    + "values (1, ?, ?, '기존 글', current_timestamp, current_timestamp)";
            String hash = "a".repeat(64);
            jdbc.update(insert, "before-migration", hash);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V83__add_feed_board_type.sql"));
            jdbc.update(insert, "old-client-after-migration", hash);
            assertThat(jdbc.queryForList("select board_type from feed_posts", String.class))
                    .containsExactly("VERIFICATION", "VERIFICATION");
            assertThat(jdbc.queryForList("select request_hash from feed_posts", String.class)).containsExactly(hash, hash);
            jdbc.update("update feed_posts set board_type='FREE' where client_post_id='old-client-after-migration'");
            assertThat(jdbc.queryForObject("select count(*) from feed_posts where board_type='FREE'", Long.class)).isEqualTo(1);
            // V84: 기존 글은 루틴 연결 없이 남고 백필하지 않음
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V84__add_feed_post_routine_link.sql"));
            assertThat(jdbc.queryForObject("select count(*) from feed_posts where routine_id is null and routine_date is null "
                    + "and routine_title is null", Long.class)).isEqualTo(2);
            jdbc.update("update feed_posts set routine_id=5, routine_date=date '2026-10-04', routine_title=? "
                    + "where client_post_id='before-migration'", "가".repeat(160));
            assertThat(jdbc.queryForObject("select routine_date from feed_posts where routine_id=5", java.sql.Date.class))
                    .hasToString("2026-10-04");
        }
    }
}
