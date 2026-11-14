package com.triples.rougether.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class ChatRichMessageMigrationTest {
    // 구버전 코드가 쓰는 INSERT. 새 컬럼(caption, emoticon_key, sort_order)을 모름.
    private static final String OLD_MESSAGE_INSERT = "insert into chat_messages (room_id, message_sequence, sender_user_id, "
            + "client_message_id, content, created_at, message_type) values (1, ?, 1, ?, ?, current_timestamp, ?)";
    private static final String OLD_IMAGE_INSERT = "insert into chat_images (room_id, owner_id, message_id, storage_key, "
            + "width, height, ready, expires_at, created_at) values (1, 1, ?, ?, 48, 32, true, current_timestamp, current_timestamp)";

    @Test
    void 기존_행과_구버전_INSERT는_그대로_동작하고_여러_장_이모티콘_공감을_저장할_수_있다() throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:chat-rich-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            for (String script : new String[] {"V1__init_schema.sql", "V77__add_chat.sql", "V102__add_chat_images.sql",
                    "V103__add_chat_message_type.sql"}) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + script));
            }
            jdbc.update("insert into users (id, created_at, updated_at) values (1, current_timestamp, current_timestamp)");
            jdbc.update("insert into users (id, created_at, updated_at) values (2, current_timestamp, current_timestamp)");
            jdbc.update("insert into chat_rooms (id, room_type) values (1, 'HOUSE')");
            jdbc.update(OLD_MESSAGE_INSERT, 1, "before", "사진을 보냈어요", "IMAGE");
            Long before = jdbc.queryForObject("select id from chat_messages where client_message_id = 'before'", Long.class);
            jdbc.update(OLD_IMAGE_INSERT, before, "private/chat/before.jpg");
            // 지금까지는 메시지 하나에 사진 한 장만 붙일 수 있었음.
            assertThatThrownBy(() -> jdbc.update(OLD_IMAGE_INSERT, before, "private/chat/second.jpg"))
                    .isInstanceOf(DataAccessException.class);

            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V108__add_chat_rich_messages.sql"));

            // 기존 행은 설명 글·이모티콘 없이 남고 사진 순서는 0임.
            assertThat(jdbc.queryForObject("select count(*) from chat_messages where caption is null "
                    + "and emoticon_key is null", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select sort_order from chat_images where message_id = ?", Integer.class, before))
                    .isZero();
            // 새 컬럼을 모르는 구버전 INSERT가 계속 성공함.
            jdbc.update(OLD_MESSAGE_INSERT, 2, "old-after", "구버전 글", "TEXT");
            jdbc.update(OLD_IMAGE_INSERT, null, "private/chat/old-after.jpg");

            // 한 메시지에 사진 여러 장을 순서와 함께 붙일 수 있음.
            jdbc.update("insert into chat_images (room_id, owner_id, message_id, sort_order, storage_key, width, height, "
                    + "ready, expires_at, created_at) values (1, 1, ?, 1, 'private/chat/second.jpg', 48, 32, true, "
                    + "current_timestamp, current_timestamp)", before);
            assertThat(jdbc.queryForList("select storage_key from chat_images where message_id = ? order by sort_order",
                    String.class, before)).containsExactly("private/chat/before.jpg", "private/chat/second.jpg");
            assertThat(jdbc.queryForObject("select count(*) from information_schema.indexes "
                    + "where table_name = 'chat_images' and index_name = 'idx_chat_image_message_order'", Long.class))
                    .isPositive();
            // 없는 메시지를 가리키는 사진은 여전히 막힘(FK 유지).
            assertThatThrownBy(() -> jdbc.update(OLD_IMAGE_INSERT, 999, "private/chat/orphan.jpg"))
                    .isInstanceOf(DataAccessException.class);

            jdbc.update("insert into chat_messages (room_id, message_sequence, sender_user_id, client_message_id, content, "
                    + "created_at, message_type, emoticon_key) values (1, 3, 1, 'emoticon', '이모티콘을 보냈어요', "
                    + "current_timestamp, 'EMOTICON', 'ruti_hello')");
            jdbc.update("insert into chat_messages (room_id, message_sequence, sender_user_id, client_message_id, content, "
                    + "created_at, message_type, caption) values (1, 4, 1, 'caption', '설명', current_timestamp, 'IMAGE', '설명')");
            assertThatThrownBy(() -> jdbc.update(OLD_MESSAGE_INSERT, 5, "bad-type", "글", "VIDEO"))
                    .isInstanceOf(DataAccessException.class);

            String reaction = "insert into chat_message_reactions (message_id, room_id, user_id, reaction_type, created_at) "
                    + "values (?, 1, ?, ?, current_timestamp)";
            jdbc.update(reaction, before, 1, "HEART");
            jdbc.update(reaction, before, 2, "SAD");
            // 한 사람은 메시지당 공감 하나만 가짐.
            assertThatThrownBy(() -> jdbc.update(reaction, before, 1, "LIKE")).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update(reaction, before, 2, "ANGRY")).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update(reaction, 999, 2, "HEART")).isInstanceOf(DataAccessException.class);
            assertThat(jdbc.queryForObject("select count(*) from chat_message_reactions", Long.class)).isEqualTo(2);
        }
    }
}
