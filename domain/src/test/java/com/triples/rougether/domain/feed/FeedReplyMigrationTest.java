package com.triples.rougether.domain.feed;

import static org.assertj.core.api.Assertions.*;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class FeedReplyMigrationTest {
    @Test void 기존댓글과_해시를_보존하고_답글대상_외래키와_게시글삭제를_지원한다() throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:feed-reply-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V1__init_schema.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V79__add_feed.sql"));
            jdbc.update("insert into users (id,created_at,updated_at) values (1,current_timestamp,current_timestamp)");
            jdbc.update("insert into feed_posts (id,author_id,client_post_id,request_hash,content,created_at,updated_at) "
                    + "values (1,1,'post',?,'글',current_timestamp,current_timestamp)", "a".repeat(64));
            jdbc.update("insert into feed_comments (id,post_id,author_id,client_comment_id,request_hash,content,created_at) "
                    + "values (1,1,1,'root',?,'기존 댓글',current_timestamp)", "b".repeat(64));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V93__add_feed_comment_replies.sql"));
            var root = jdbc.queryForMap("select content,request_hash,parent_comment_id,reply_to_comment_id from feed_comments where id=1");
            assertThat(root.get("content")).isEqualTo("기존 댓글");
            assertThat(root.get("request_hash")).isEqualTo("b".repeat(64));
            assertThat(root.get("parent_comment_id")).isNull();
            assertThat(root.get("reply_to_comment_id")).isNull();
            String insert = "insert into feed_comments (id,post_id,author_id,client_comment_id,request_hash,content,created_at,parent_comment_id,reply_to_comment_id) "
                    + "values (?,1,1,?,?, '답글',current_timestamp,1,?)";
            jdbc.update(insert, 2, "reply", "c".repeat(64), 1);
            jdbc.update(insert, 3, "reply-to-reply", "d".repeat(64), 2);
            assertThatThrownBy(() -> jdbc.update(insert, 4, "missing", "e".repeat(64), 999))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            jdbc.update("delete from feed_comments where id=2");
            assertThat(jdbc.queryForObject("select reply_to_comment_id from feed_comments where id=3", Long.class)).isNull();
            assertThat(jdbc.queryForObject("select parent_comment_id from feed_comments where id=3", Long.class)).isEqualTo(1);
            jdbc.update("delete from feed_comments where post_id=1");
            assertThat(jdbc.queryForObject("select count(*) from feed_comments", Long.class)).isZero();
        }
    }
}
