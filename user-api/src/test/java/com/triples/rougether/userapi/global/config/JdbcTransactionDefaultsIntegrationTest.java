package com.triples.rougether.userapi.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.infra.fcm.FcmSender;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:mysql:8.4:///jdbc_transaction_defaults",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1"
})
abstract class JdbcTransactionDefaultsIntegrationTest {
    // 실제 데이터소스로 검증하고 외부 푸시 발송은 대체한다.
    @MockitoBean FcmSender fcmSender;
    @Autowired DataSource dataSource;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void poolAndHibernateAgreeOnManualCommit() throws Exception {
        assertThat(dataSource.unwrap(HikariDataSource.class).isAutoCommit()).isFalse();
        assertThat(entityManagerFactory.getProperties()
                .get("hibernate.connection.provider_disables_autocommit").toString()).isEqualTo("true");
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getAutoCommit()).isFalse();
        }
    }

    @Test
    void successfulTransactionPersistsAndFailedTransactionRollsBack() {
        var tx = new TransactionTemplate(transactionManager);
        Long committed = tx.execute(status -> users.saveAndFlush(User.signUp()).getId());
        assertThat(users.findById(committed)).isPresent();

        var rolledBack = new AtomicReference<Long>();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            rolledBack.set(users.saveAndFlush(User.signUp()).getId());
            throw new IllegalStateException("rollback-check");
        })).isInstanceOf(IllegalStateException.class).hasMessage("rollback-check");
        assertThat(rolledBack.get()).isNotNull();
        assertThat(users.findById(rolledBack.get())).isEmpty();
        assertThat(users.findById(committed)).isPresent();
    }

    @Test
    void writeTransactionDoesNotSendSessionSetCommands() throws Exception {
        var before = sessionCounters();
        Long id = new TransactionTemplate(transactionManager)
                .execute(status -> users.saveAndFlush(User.signUp()).getId());
        var after = sessionCounters();
        assertThat(after.connectionId()).isEqualTo(before.connectionId());
        assertThat(after.setCommands()).isEqualTo(before.setCommands());
        assertThat(users.findById(id)).isPresent();
    }

    private SessionCounters sessionCounters() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            long id;
            try (var result = statement.executeQuery("SELECT CONNECTION_ID()")) {
                result.next();
                id = result.getLong(1);
            }
            try (var result = statement.executeQuery("SHOW SESSION STATUS LIKE 'Com_set_option'")) {
                assertThat(result.next()).isTrue();
                return new SessionCounters(id, result.getLong(2));
            }
        }
    }

    private record SessionCounters(long connectionId, long setCommands) {}

    @Test
    void readOnlyTransactionDoesNotPreventTheNextWrite() throws Exception {
        var read = new TransactionTemplate(transactionManager);
        read.setReadOnly(true);
        read.execute(status -> users.count());

        Long id = new TransactionTemplate(transactionManager)
                .execute(status -> users.saveAndFlush(User.signUp()).getId());
        assertThat(users.findById(id)).isPresent();
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getAutoCommit()).isFalse();
            assertThat(connection.isReadOnly()).isFalse();
        }
    }
}

@ActiveProfiles("prod")
class ProdJdbcTransactionDefaultsTest extends JdbcTransactionDefaultsIntegrationTest {}

@ActiveProfiles("mysql")
class MysqlJdbcTransactionDefaultsTest extends JdbcTransactionDefaultsIntegrationTest {}
