package com.triples.rougether.lambdacontrol;

import static org.assertj.core.api.Assertions.*;
import com.triples.rougether.furniture.ai.FurnitureAiClient;
import com.triples.rougether.furniture.lambda.*;
import com.triples.rougether.furniture.lambda.FurnitureLambdaProtocol.*;
import com.triples.rougether.infra.assets.AssetStorageService;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(classes = ControlApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.flyway.enabled=true", "billing.require-credits=false"})
class ControlBootTest {
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    static { MYSQL.start(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
    }
    @Autowired ApplicationContext context;
    @Autowired FurnitureLambdaControl control;
    @Autowired DataSource source;
    @Test void 실제_핸들러에서_새_전처리_명령과_기존_AI_명령을_함께_처리함() throws Exception {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var handler = new ControlHandler(context);
        String job = UUID.randomUUID().toString();
        var command = com.triples.rougether.common.furniture.FurniturePreprocessProtocol.Command.start(job,
                com.triples.rougether.common.furniture.FurniturePreprocessProtocol.sourceKey(job), "source-v1");
        var output = new java.io.ByteArrayOutputStream();
        handler.handleRequest(new java.io.ByteArrayInputStream(json.writeValueAsBytes(command)), output, null);
        assertThat(json.readTree(output.toByteArray()).path("status").asString()).isEqualTo("STALE");
        output.reset();
        handler.handleRequest(new java.io.ByteArrayInputStream(json.writeValueAsBytes(
                new Command(job, UUID.randomUUID().toString(), "request", 0, null))), output, null);
        assertThat(json.readTree(output.toByteArray()).path("status").asString()).isEqualTo("TERMINAL");
    }
    @Test void DB_제어는_AI와_S3_빈없이_시작하며_연결풀을_보유하지않음() throws Exception {
        assertThat(context.getBeansOfType(FurnitureAiClient.class)).isEmpty();
        assertThat(context.getBeansOfType(AssetStorageService.class)).isEmpty();
        assertThat(source).isInstanceOf(org.springframework.jdbc.datasource.DriverManagerDataSource.class);
        for (int i = 0; i < 4; i++) {
            assertThat(control.handle(new Command(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "request", 0, null)).status()).isEqualTo(Status.TERMINAL);
        }
        try (var c = source.getConnection(); var statement = c.createStatement();
                var rows = statement.executeQuery("select count(*) from information_schema.processlist where user = current_user() or user = 'test'")) {
            rows.next(); assertThat(rows.getInt(1)).isLessThanOrEqualTo(1);
        }
    }
}
