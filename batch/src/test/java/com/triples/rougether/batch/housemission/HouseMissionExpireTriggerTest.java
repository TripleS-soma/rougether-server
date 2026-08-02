package com.triples.rougether.batch.housemission;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

// 단체 미션 만료 전이 (#205) - ends_at 이 지난 ACTIVE 만 EXPIRED 로 내리고, 재실행은 멱등이다.
// 전체 배치 컨텍스트 대신 트리거만 올린다 (job 다중 등록으로 풀 컨텍스트가 안 뜨는 기존 테스트 패턴 준용).
@SpringBootTest(classes = HouseMissionExpireTriggerTest.TestConfig.class)
class HouseMissionExpireTriggerTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(HouseMissionExpireTrigger.class)
    static class TestConfig {
    }

    @Autowired private HouseMissionExpireTrigger trigger;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long userId;
    private Long houseId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO users (nickname, created_at, updated_at) VALUES ('만료테스트', ?, ?)",
                Timestamp.from(now), Timestamp.from(now));
        userId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM users", Long.class);
        jdbcTemplate.update("""
                INSERT INTO house (owner_user_id, name, current_member_count, level, growth_points,
                                   created_at, updated_at)
                VALUES (?, '만료 테스트 하우스', 1, 0, 0, ?, ?)
                """, userId, Timestamp.from(now), Timestamp.from(now));
        houseId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM house", Long.class);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM house_missions WHERE house_id = ?", houseId);
        jdbcTemplate.update("DELETE FROM house WHERE id = ?", houseId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    private Long insertMission(String title, String status, Instant endsAt) {
        jdbcTemplate.update("""
                INSERT INTO house_missions (house_id, title, mission_type, target_value, status,
                                            starts_at, ends_at, created_at)
                VALUES (?, ?, 'WEEKLY_MEMBER_COUNT', 10, ?, NULL, ?, ?)
                """, houseId, title, status,
                endsAt == null ? null : Timestamp.from(endsAt), Timestamp.from(Instant.now()));
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM house_missions", Long.class);
    }

    private String statusOf(Long missionId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM house_missions WHERE id = ?", String.class, missionId);
    }

    @Test
    void 기간이_지난_ACTIVE_미션만_EXPIRED_로_전이한다() {
        Instant past = Instant.now().minus(Duration.ofDays(1));
        Instant future = Instant.now().plus(Duration.ofDays(1));
        Long overdue = insertMission("기간 지남", "ACTIVE", past);
        Long ongoing = insertMission("진행 중", "ACTIVE", future);
        Long endless = insertMission("무기한", "ACTIVE", null);
        Long completed = insertMission("완료됨", "COMPLETED", past);

        trigger.expireOverdueMissions();

        assertThat(statusOf(overdue)).isEqualTo("EXPIRED");
        assertThat(statusOf(ongoing)).isEqualTo("ACTIVE");
        assertThat(statusOf(endless)).isEqualTo("ACTIVE");
        assertThat(statusOf(completed)).isEqualTo("COMPLETED");

        // 재실행 멱등 - 이미 EXPIRED 인 행은 그대로
        trigger.expireOverdueMissions();
        assertThat(statusOf(overdue)).isEqualTo("EXPIRED");
        assertThat(statusOf(completed)).isEqualTo("COMPLETED");
    }
}
