package com.triples.rougether.domain.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoomCharacterScaleMigrationTest {
    @TempDir Path migrations;

    @Test
    void addsDefaultBeforeFutureEventMigrationsWithoutOutOfOrder() throws Exception {
        String url = "jdbc:h2:mem:character_scale_migration;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE personal_rooms (user_id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO personal_rooms (user_id) VALUES (1)");
        }
        String name = "V101_1__add_room_character_scale.sql";
        try (var source = getClass().getResourceAsStream("/db/migration/" + name)) {
            Files.copy(source, migrations.resolve(name));
        }
        Flyway flyway = Flyway.configure().dataSource(url, "sa", "")
                .locations("filesystem:" + migrations).baselineOnMigrate(true)
                .baselineVersion("101").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("101.1");
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("INSERT INTO personal_rooms (user_id) VALUES (2)");
            try (var rows = statement.executeQuery("SELECT character_scale FROM personal_rooms ORDER BY user_id")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBigDecimal(1)).isEqualByComparingTo("1.00");
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBigDecimal(1)).isEqualByComparingTo("1.00");
            }
        }
        Files.writeString(migrations.resolve("V102__future_feature.sql"), "CREATE TABLE future_feature (id INT);");
        Files.writeString(migrations.resolve("V103__future_data.sql"), "INSERT INTO future_feature VALUES (1);");
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("103");
    }
}
