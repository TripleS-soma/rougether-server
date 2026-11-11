package com.triples.rougether.domain.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class HouseFeedNotificationSettingMigrationTest {
    @Test
    void 설정_행은_자유글_켜짐_인증글_꺼짐으로_시작하고_집과_회원당_한_행만_둔다() throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:house-feed-notification-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V106__add_house_feed_notification_settings.sql"));
            String insert = "insert into house_feed_notification_settings (house_id, user_id, created_at, updated_at) "
                    + "values (?, ?, current_timestamp, current_timestamp)";
            jdbc.update(insert, 7, 1);
            var row = jdbc.queryForMap("select free_post_push, verification_post_push from house_feed_notification_settings");
            assertThat(row.get("free_post_push")).isEqualTo(true);
            assertThat(row.get("verification_post_push")).isEqualTo(false);
            // 같은 사람이라도 집이 다르면 따로 저장하고, 같은 집에는 한 행만 허용함
            jdbc.update(insert, 8, 1);
            assertThatThrownBy(() -> jdbc.update(insert, 7, 1)).isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
