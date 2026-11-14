package com.triples.rougether.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class ChatImageRetentionMigrationTest {
    @Test
    void 기존_전송_사진만_전송부터_30일로_보정하고_미전송_만료와_구버전_INSERT는_유지한다() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:retention-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            for (String name : new String[] {"V1__init_schema.sql", "V77__add_chat.sql", "V102__add_chat_images.sql",
                    "V103__add_chat_message_type.sql", "V108__add_chat_rich_messages.sql"})
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + name));
            jdbc.update("insert into users (id,created_at,updated_at) values (1,current_timestamp,current_timestamp)");
            jdbc.update("insert into chat_rooms (id,room_type) values (1,'HOUSE')");
            Instant sent = Instant.parse("2026-10-01T00:00:00Z");
            jdbc.update("insert into chat_messages (id,room_id,message_sequence,sender_user_id,client_message_id,"
                    + "content,created_at,message_type) values (1,1,1,1,'existing','photo',?,'IMAGE')", Timestamp.from(sent));
            String insert = "insert into chat_images (room_id,owner_id,message_id,storage_key,width,height,ready,"
                    + "expires_at,created_at) values (1,1,?,?,48,32,true,?,?)";
            jdbc.update(insert, 1L, "private/chat/sent.jpg", Timestamp.from(sent.plusSeconds(86400)), Timestamp.from(sent));
            jdbc.update(insert, null, "private/chat/unsent.jpg", Timestamp.from(sent.plusSeconds(86400)), Timestamp.from(sent));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V109__expire_sent_chat_images.sql"));
            assertThat(jdbc.queryForObject("select expires_at from chat_images where message_id=1", Timestamp.class))
                    .isEqualTo(Timestamp.from(sent.plus(Duration.ofDays(30))));
            assertThat(jdbc.queryForObject("select expires_at from chat_images where message_id is null", Timestamp.class))
                    .isEqualTo(Timestamp.from(sent.plusSeconds(86400)));
            jdbc.update(insert, null, "private/chat/old-writer.jpg", Timestamp.from(sent.plusSeconds(86400)), Timestamp.from(sent));
            assertThat(jdbc.queryForObject("select count(*) from chat_images where purged_at is null", Long.class)).isEqualTo(3);
        }
    }
}
