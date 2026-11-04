package com.triples.rougether.batch.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.moderation.entity.ContentReport;
import com.triples.rougether.domain.moderation.entity.ContentReportStatus;
import com.triples.rougether.domain.moderation.repository.ContentReportRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

// 운영자 대기열 위험도 정렬(#479) 쿼리를 실제 MySQL 에서 검증한다. 쿼리는 domain 에 있고, MySQL 컨테이너가 batch 테스트에 있어 여기 둠.
@SpringBootTest(classes = ContentReportRiskOrderIntegrationTest.TestConfig.class)
class ContentReportRiskOrderIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @org.springframework.boot.persistence.autoconfigure.EntityScan("com.triples.rougether.domain")
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories("com.triples.rougether.domain")
    static class TestConfig {
    }

    // 다른 테스트의 잔여 신고와 섞이지 않게 큰 대상 id 대역을 씀(대상은 논리 참조라 FK 없음)
    private static final long BASE = 800_000_000L + (System.nanoTime() % 1_000_000L) * 10;

    @Autowired private ContentReportRepository reports;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM content_screenings WHERE target_id BETWEEN ? AND ?", BASE, BASE + 9);
        jdbc.update("DELETE FROM content_reports WHERE target_id BETWEEN ? AND ?", BASE, BASE + 9);
        userIds.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
    }

    @Test
    void 대기_신고는_유해_확률_내림차순이고_점수_없음은_뒤_동점은_최신순이며_처리된_신고는_빠진다() {
        Long reporter = user();
        long high = BASE, mid = BASE + 1, tieOld = BASE + 2, tieNew = BASE + 3, none = BASE + 4, closed = BASE + 5;
        Long noneReport = report(reporter, "FEED_POST", none, "RECEIVED");
        Long midReport = report(reporter, "FEED_POST", mid, "RECEIVED");
        Long tieOldReport = report(reporter, "FEED_COMMENT", tieOld, "RECEIVED");
        Long highReport = report(reporter, "FEED_POST", high, "RECEIVED");
        Long tieNewReport = report(reporter, "FEED_COMMENT", tieNew, "RECEIVED");
        report(reporter, "FEED_POST", closed, "DISMISSED");
        screening("FEED_POST", high, "0.9500");
        screening("FEED_POST", mid, "0.6000");
        screening("FEED_COMMENT", tieOld, "0.3000");
        screening("FEED_COMMENT", tieNew, "0.3000");
        screening("FEED_POST", closed, "0.9900");

        List<Long> ordered = reports.findReceivedByRisk(PageRequest.of(0, 100)).getContent().stream()
                .filter(r -> r.getTargetId() >= BASE && r.getTargetId() <= BASE + 9)
                .map(ContentReport::getId).toList();

        assertThat(ordered).containsExactly(highReport, midReport, tieNewReport, tieOldReport, noneReport);
        assertThat(reports.findByStatusOrderByIdDesc(ContentReportStatus.RECEIVED, PageRequest.of(0, 100))
                .getContent().stream().filter(r -> r.getTargetId() >= BASE && r.getTargetId() <= BASE + 9)
                .map(ContentReport::getId).toList())
                .containsExactly(tieNewReport, highReport, tieOldReport, midReport, noneReport);
    }

    @Test
    void 같은_대상에_신고가_여러_건이어도_각각_나오고_전체_건수는_대기_신고_수와_같다() {
        Long first = user();
        Long second = user();
        long target = BASE + 6;
        report(first, "FEED_POST", target, "RECEIVED");
        report(second, "FEED_POST", target, "RECEIVED");
        screening("FEED_POST", target, "0.7000");

        long receivedTotal = jdbc.queryForObject("SELECT COUNT(*) FROM content_reports WHERE status = 'RECEIVED'",
                Long.class);
        var page = reports.findReceivedByRisk(PageRequest.of(0, 500));
        assertThat(page.getTotalElements()).isEqualTo(receivedTotal);
        assertThat(page.getContent().stream().filter(r -> r.getTargetId() == target)).hasSize(2);
    }

    private Long user() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO users (nickname, created_at, updated_at) VALUES ('정렬 테스트', ?, ?)", now, now);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM users", Long.class);
        userIds.add(id);
        return id;
    }

    private Long report(Long reporter, String type, long targetId, String status) {
        jdbc.update("INSERT INTO content_reports (reporter_user_id, target_type, target_id, reason, status, created_at) "
                + "VALUES (?, ?, ?, 'ABUSE', ?, ?)", reporter, type, targetId, status, Timestamp.from(Instant.now()));
        return jdbc.queryForObject("SELECT MAX(id) FROM content_reports", Long.class);
    }

    private void screening(String type, long targetId, String probability) {
        jdbc.update("INSERT INTO content_screenings (target_type, target_id, content_hash, model, harm_probability, "
                + "top_reason, probabilities, input_tokens, flagged, screened_at) "
                + "VALUES (?, ?, ?, 'jev-1.14.0', ?, 'ABUSE', '{}', 1, FALSE, ?)",
                type, targetId, "a".repeat(64), probability, Timestamp.from(Instant.now()));
    }
}
