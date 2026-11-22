package com.triples.rougether.batch.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest(classes = WithdrawalPurgeTriggerTest.TestConfig.class, properties = {
        "spring.datasource.url=jdbc:tc:mysql:8.4:///withdrawal_purge_upgrade",
        "spring.flyway.target=108"
})
@ExtendWith(OutputCaptureExtension.class)
class WithdrawalInventoryPurgeUpgradeTest {

    @Autowired private WithdrawalPurgeTrigger trigger;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @Test
    @Sql("/withdrawal/inventory-purge-v108.sql")
    void 마이그레이션_전_FK로_실패한_계정을_다음_배치가_회수한다(CapturedOutput logs) {
        trigger.purgeWithdrawnUsers();

        assertThat(logs.getOut()).contains("fk_furniture_job_inventory", "fk_market_purchases_item");
        assertThat(purgedUsers()).isEmpty();
        // 아이템 삭제에 앞서 지웠던 카테고리까지 롤백돼야 한다.
        assertThat(jdbc.queryForList("SELECT user_id FROM categories ORDER BY user_id", Long.class))
                .containsExactly(9001L, 9002L, 9003L, 9004L);

        Flyway.configure().dataSource(dataSource).target("109").load().migrate();
        trigger.purgeWithdrawnUsers();

        assertThat(purgedUsers()).containsExactly(9001L, 9002L);
        assertThat(jdbc.queryForList("SELECT user_id FROM categories ORDER BY user_id", Long.class))
                .containsExactly(9003L, 9004L);
        assertThat(jdbc.queryForList("SELECT id FROM user_items ORDER BY id", Long.class))
                .containsExactly(9103L, 9104L);
        assertThat(jdbc.queryForObject(
                "SELECT user_item_id FROM furniture_generation_jobs WHERE id = 'purge-job'", Long.class)).isNull();
        assertThat(jdbc.queryForList(
                "SELECT user_item_id FROM market_purchases WHERE seller_user_id = 9001 ORDER BY buyer_user_id",
                Long.class)).containsExactly(null, 9103L);

        // 미체결 매도의 담보는 주문 정산까지 보존하고, 정산 후 다음 실행에서 분리한다.
        assertThat(legacyOrderItem()).isEqualTo(9104L);
        assertThat(legacyCommandItem()).isEqualTo(9104L);
        jdbc.update("UPDATE market_orders SET status = 'CANCELLED', escrow_remaining = 0 WHERE command_id = 9300");
        Timestamp firstPurge = jdbc.queryForObject("SELECT purged_at FROM users WHERE id = 9001", Timestamp.class);
        trigger.purgeWithdrawnUsers();

        assertThat(purgedUsers()).containsExactly(9001L, 9002L, 9004L);
        assertThat(jdbc.queryForObject("SELECT purged_at FROM users WHERE id = 9001", Timestamp.class))
                .isEqualTo(firstPurge);
        assertThat(legacyOrderItem()).isNull();
        assertThat(legacyCommandItem()).isNull();
        assertThat(jdbc.queryForList("SELECT id FROM user_items ORDER BY id", Long.class))
                .containsExactly(9103L);
    }

    private List<Long> purgedUsers() {
        return jdbc.queryForList("SELECT id FROM users WHERE purged_at IS NOT NULL ORDER BY id", Long.class);
    }

    private Long legacyOrderItem() {
        return jdbc.queryForObject("SELECT user_item_id FROM market_orders WHERE command_id = 9300", Long.class);
    }

    private Long legacyCommandItem() {
        return jdbc.queryForObject("SELECT escrow_user_item_id FROM market_commands WHERE id = 9300", Long.class);
    }
}
