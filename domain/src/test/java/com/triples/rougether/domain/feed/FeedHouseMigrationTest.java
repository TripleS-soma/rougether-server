package com.triples.rougether.domain.feed;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class FeedHouseMigrationTest {
    @Test
    void 기존글은_공개글로_남고_집글은_집_ID를_보관한다() throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:feed-house-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V1__init_schema.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V79__add_feed.sql"));
            jdbc.update("insert into users (id, created_at, updated_at) values (1, current_timestamp, current_timestamp)");
            String insert = "insert into feed_posts (author_id, client_post_id, request_hash, content, created_at, updated_at) "
                    + "values (1, ?, ?, '기존 글', current_timestamp, current_timestamp)";
            jdbc.update(insert, "before-migration", "a".repeat(64));

            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V105__add_feed_post_house.sql"));

            // 기존 글과 house_id 를 모르는 구버전 등록은 공개 글(NULL)로 남음
            jdbc.update(insert, "old-client-after-migration", "b".repeat(64));
            assertThat(jdbc.queryForObject("select count(*) from feed_posts where house_id is null", Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select count(*) from information_schema.indexes "
                    + "where table_name = 'feed_posts' and index_name = 'idx_feed_post_house_page'", Long.class)).isEqualTo(1);
            // FK 없이 집 ID만 보관함. 집 존재와 구성원 여부는 등록 시 서비스가 검사함
            assertThat(jdbc.queryForObject("select count(*) from information_schema.referential_constraints "
                    + "where constraint_name like 'fk_feed_post_house%'", Long.class)).isZero();
            jdbc.update("update feed_posts set house_id = 7 where client_post_id = 'before-migration'");
            assertThat(jdbc.queryForObject("select client_post_id from feed_posts where house_id = 7", String.class))
                    .isEqualTo("before-migration");
        }
    }
}
