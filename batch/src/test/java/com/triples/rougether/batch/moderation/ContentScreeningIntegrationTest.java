package com.triples.rougether.batch.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.batch.alert.BatchFailureAlertNotifier;
import com.triples.rougether.domain.moderation.entity.ContentReportStatus;
import com.triples.rougether.domain.moderation.entity.ContentReportTargetType;
import com.triples.rougether.domain.moderation.repository.ContentReportRepository;
import com.triples.rougether.domain.moderation.repository.ScreeningCandidate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

// 실제 MySQL 에서 후보 선정 SQL·해시 재판정·자동 신고 정합성과 migration CHECK 를 검증한다. Jev 호출만 모킹함.
@SpringBootTest(classes = ContentScreeningIntegrationTest.TestConfig.class,
        properties = "rougether.moderation.screening.api-key=ts-test")
class ContentScreeningIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @org.springframework.boot.persistence.autoconfigure.EntityScan("com.triples.rougether.domain")
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories("com.triples.rougether.domain")
    @Import({ContentScreeningConfig.class, ContentScreeningTrigger.class, ContentScreeningCandidateReader.class,
            ContentScreeningRecorder.class})
    static class TestConfig {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    private static final String HARMFUL = "욕설_판정";

    @MockitoBean private JevClient jev;
    @MockitoBean private BatchFailureAlertNotifier notifier;
    @Autowired private ContentScreeningTrigger trigger;
    @Autowired private ContentScreeningRecorder recorder;
    @Autowired private ContentReportRepository reports;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> postIds = new ArrayList<>();

    private static JevVerdict verdict(double abuse) {
        return new JevVerdict("jev-1.14.0", Map.of("SPAM", 0.0, "ABUSE", abuse, "SEXUAL", 0.0, "VIOLENCE", 0.0,
                "PERSONAL_INFO", 0.0, "NONE", 1 - abuse), 42);
    }

    @BeforeEach
    void stubJev() {
        when(jev.classify(anyString(), any())).thenAnswer(call ->
                ((String) call.getArgument(0)).contains(HARMFUL) ? verdict(0.8) : verdict(0.1));
    }

    @AfterEach
    void cleanup() {
        for (Long postId : postIds) {
            jdbc.update("DELETE FROM content_screenings WHERE (target_type = 'FEED_POST' AND target_id = ?) "
                    + "OR (target_type = 'FEED_COMMENT' AND target_id IN (SELECT id FROM feed_comments WHERE post_id = ?))",
                    postId, postId);
            jdbc.update("DELETE FROM content_reports WHERE (target_type = 'FEED_POST' AND target_id = ?) "
                    + "OR (target_type = 'FEED_COMMENT' AND target_id IN (SELECT id FROM feed_comments WHERE post_id = ?))",
                    postId, postId);
            jdbc.update("DELETE FROM feed_comments WHERE post_id = ?", postId);
            jdbc.update("DELETE FROM feed_posts WHERE id = ?", postId);
        }
        for (Long userId : userIds) {
            jdbc.update("DELETE FROM content_reports WHERE reporter_user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        postIds.clear();
        userIds.clear();
    }

    @Test
    void 창_안의_살아있는_사람_글과_댓글만_판정하고_댓글은_원글_앞부분을_문맥으로_보낸다() {
        Instant now = Instant.now();
        Long human = user(false, false, false);
        Long post = post(human, "평범한 글 " + UUID.randomUUID(), now.minus(Duration.ofDays(2)));
        Long comment = comment(post, human, "평범한 댓글 " + UUID.randomUUID(), now.minus(Duration.ofDays(1)));
        Long old = post(human, "오래된 글", now.minus(Duration.ofDays(8)));
        Long deleted = post(human, "삭제된 글", now.minus(Duration.ofDays(1)));
        jdbc.update("UPDATE feed_posts SET deleted_at = ? WHERE id = ?", Timestamp.from(now), deleted);
        Long commentOnDeleted = comment(deleted, human, "삭제된 글의 댓글", now.minus(Duration.ofHours(1)));
        Long blank = post(human, "   ", now.minus(Duration.ofHours(1)));
        Long ai = user(true, false, false);
        Long bot = user(false, true, false);
        Long leaver = user(false, false, true);
        Long aiPost = post(ai, "AI 글", now.minus(Duration.ofHours(1)));
        Long botPost = post(bot, "봇 글", now.minus(Duration.ofHours(1)));
        Long leaverPost = post(leaver, "탈퇴자 글", now.minus(Duration.ofHours(1)));
        Long justInside = post(human, "창 안쪽 경계 " + UUID.randomUUID(), now.minus(Duration.ofDays(7)).plus(Duration.ofHours(1)));
        Long justOutside = post(human, "창 바깥 경계", now.minus(Duration.ofDays(7)).minus(Duration.ofHours(1)));
        Long aiComment = comment(post, ai, "AI 댓글", now.minus(Duration.ofHours(1)));
        Long botComment = comment(post, bot, "봇 댓글", now.minus(Duration.ofHours(1)));
        Long leaverComment = comment(post, leaver, "탈퇴자 댓글", now.minus(Duration.ofHours(1)));

        trigger.screen();

        assertThat(screened("FEED_POST", post)).isTrue();
        assertThat(screened("FEED_POST", justInside)).isTrue();
        assertThat(screened("FEED_COMMENT", comment)).isTrue();
        for (Long excluded : List.of(old, justOutside, deleted, blank, aiPost, botPost, leaverPost)) {
            assertThat(screened("FEED_POST", excluded)).as("post %s", excluded).isFalse();
        }
        for (Long excluded : List.of(commentOnDeleted, aiComment, botComment, leaverComment)) {
            assertThat(screened("FEED_COMMENT", excluded)).as("comment %s", excluded).isFalse();
        }
        String postContent = jdbc.queryForObject("SELECT content FROM feed_posts WHERE id = ?", String.class, post);
        verify(jev).classify(eq(postContent), isNull());
        verify(jev).classify(anyString(), eq(postContent));
    }

    @Test
    void 기준_이상만_자동_신고하고_재실행해도_다시_판정하거나_중복_신고하지_않는다() {
        Instant now = Instant.now();
        Long author = user(false, false, false);
        Long harmful = post(author, HARMFUL + " " + UUID.randomUUID(), now.minus(Duration.ofHours(1)));
        Long benign = post(author, "점심 추천 좀 " + UUID.randomUUID(), now.minus(Duration.ofHours(1)));

        trigger.screen();
        trigger.screen();

        Map<String, Object> report = jdbc.queryForMap("SELECT * FROM content_reports WHERE target_type = 'FEED_POST' "
                + "AND target_id = ?", harmful);
        assertThat(report.get("source")).isEqualTo("AUTO");
        assertThat(report.get("reporter_user_id")).isNull();
        assertThat(report.get("status")).isEqualTo("RECEIVED");
        assertThat(report.get("reason")).isEqualTo("ABUSE");
        assertThat(((Number) report.get("target_owner_user_id")).longValue()).isEqualTo(author);
        assertThat(report.get("detail")).isEqualTo("auto: ABUSE p=0.8000");

        Map<String, Object> screening = screening("FEED_POST", harmful);
        assertThat(screening.get("flagged")).isEqualTo(true);
        assertThat(((Number) screening.get("report_id")).longValue()).isEqualTo(((Number) report.get("id")).longValue());
        assertThat(((Number) screening.get("input_tokens")).intValue()).isEqualTo(42);
        assertThat(screening.get("content_hash")).isEqualTo(ContentScreeningTrigger.sha256(
                jdbc.queryForObject("SELECT content FROM feed_posts WHERE id = ?", String.class, harmful)));

        assertThat(screening("FEED_POST", benign).get("flagged")).isEqualTo(false);
        assertThat(reportCount("FEED_POST", benign)).isZero();
        verify(jev, times(1)).classify(eq(content(harmful)), any());
        verify(jev, times(1)).classify(eq(content(benign)), any());
    }

    @Test
    void 기준_이상_댓글도_FEED_COMMENT_자동_신고가_되고_이모지_본문도_해시가_맞아_재판정하지_않는다() {
        Instant now = Instant.now();
        Long author = user(false, false, false);
        Long post = post(author, "이모지 글 😂🔥 " + UUID.randomUUID(), now.minus(Duration.ofHours(2)));
        Long commenter = user(false, false, false);
        Long comment = comment(post, commenter, HARMFUL + " 댓글 🤬 " + UUID.randomUUID(), now.minus(Duration.ofHours(1)));

        trigger.screen();
        trigger.screen();

        Map<String, Object> report = jdbc.queryForMap("SELECT * FROM content_reports WHERE target_type = 'FEED_COMMENT' "
                + "AND target_id = ?", comment);
        assertThat(report.get("source")).isEqualTo("AUTO");
        assertThat(((Number) report.get("target_owner_user_id")).longValue()).isEqualTo(commenter);
        Map<String, Object> screening = screening("FEED_COMMENT", comment);
        assertThat(screening.get("model")).isEqualTo("jev-1.14.0");
        assertThat(screening.get("probabilities").toString()).contains("\"ABUSE\"").contains("\"NONE\"");
        assertThat(screening.get("screened_at")).isNotNull();
        verify(jev, times(1)).classify(eq(content(post)), any());
    }

    @Test
    void 일곱일_지난_글도_신고되면_점수만_붙이고_자동_신고는_만들지_않으며_거래소_신고는_판정하지_않는다() {
        Instant now = Instant.now();
        Long author = user(false, false, false);
        Long reporter = user(false, false, false);
        Long oldReported = post(author, HARMFUL + " 오래된 신고 글 " + UUID.randomUUID(), now.minus(Duration.ofDays(30)));
        Long oldUnreported = post(author, HARMFUL + " 오래된 미신고 글", now.minus(Duration.ofDays(30)));
        insertReport(reporter, "FEED_POST", oldReported, author, now);
        long marketAssetId = 900_000_000L + (System.nanoTime() % 1_000_000L);
        insertReport(reporter, "MARKET_ASSET", marketAssetId, author, now);

        trigger.screen();

        Map<String, Object> screening = screening("FEED_POST", oldReported);
        assertThat(screening.get("flagged")).isEqualTo(true);
        assertThat(screening.get("report_id")).isNull();
        assertThat(jdbc.queryForList("SELECT source FROM content_reports WHERE target_type = 'FEED_POST' "
                + "AND target_id = ?", String.class, oldReported)).containsExactly("USER");
        assertThat(screened("FEED_POST", oldUnreported)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_screenings WHERE target_id = ?",
                Integer.class, marketAssetId)).isZero();
    }

    @Test
    void 신고된_대상은_판정_사이에_신고가_처리돼도_자동_신고를_만들지_않는다() {
        Instant now = Instant.now();
        Long author = user(false, false, false);
        Long post = post(author, HARMFUL + " 처리된 신고 " + UUID.randomUUID(), now.minus(Duration.ofDays(20)));
        // 대기 신고가 없는 상태(읽은 뒤 운영자가 처리한 경우)를 재현 — 기존 RECEIVED 가드로는 막히지 않음
        ScreeningTarget reported = new ScreeningTarget(candidate("FEED_POST", post, author, content(post)), true);
        ScreeningJudgement judgement = ScreeningJudgement.of(verdict(0.8).probabilities(), 0.6);

        recorder.record(reported, ContentScreeningTrigger.sha256(content(post)), verdict(0.8), judgement, now);

        assertThat(screening("FEED_POST", post).get("flagged")).isEqualTo(true);
        assertThat(screening("FEED_POST", post).get("report_id")).isNull();
        assertThat(reportCount("FEED_POST", post)).isZero();
    }

    @Test
    void 신고된_오래된_댓글은_원글_문맥과_함께_한_번만_판정되고_삭제된_댓글은_제외된다() {
        Instant now = Instant.now();
        Long author = user(false, false, false);
        Long first = user(false, false, false);
        Long second = user(false, false, false);
        Long post = post(author, "원글 본문 " + UUID.randomUUID(), now.minus(Duration.ofDays(40)));
        Long comment = comment(post, author, HARMFUL + " 오래된 댓글 " + UUID.randomUUID(), now.minus(Duration.ofDays(30)));
        Long deletedComment = comment(post, author, HARMFUL + " 삭제된 댓글", now.minus(Duration.ofDays(30)));
        jdbc.update("UPDATE feed_comments SET deleted_at = ? WHERE id = ?", Timestamp.from(now), deletedComment);
        insertReport(first, "FEED_COMMENT", comment, author, now);
        insertReport(second, "FEED_COMMENT", comment, author, now);
        insertReport(first, "FEED_COMMENT", deletedComment, author, now);

        trigger.screen();
        trigger.screen();

        String commentContent = jdbc.queryForObject("SELECT content FROM feed_comments WHERE id = ?", String.class,
                comment);
        verify(jev, times(1)).classify(eq(commentContent), eq(content(post)));
        assertThat(screening("FEED_COMMENT", comment).get("report_id")).isNull();
        assertThat(jdbc.queryForList("SELECT source FROM content_reports WHERE target_type = 'FEED_COMMENT' "
                + "AND target_id = ?", String.class, comment)).containsExactly("USER", "USER");
        assertThat(screened("FEED_COMMENT", deletedComment)).isFalse();
    }

    private static ScreeningCandidate candidate(String type, Long id, Long owner, String content) {
        return new ScreeningCandidate() {
            public String getTargetType() { return type; }
            public Long getTargetId() { return id; }
            public Long getOwnerUserId() { return owner; }
            public String getContent() { return content; }
            public String getContext() { return null; }
        };
    }

    private void insertReport(Long reporter, String type, Long targetId, Long owner, Instant at) {
        jdbc.update("INSERT INTO content_reports (reporter_user_id, target_type, target_id, target_owner_user_id, "
                + "reason, status, created_at) VALUES (?, ?, ?, ?, 'ABUSE', 'RECEIVED', ?)",
                reporter, type, targetId, owner, Timestamp.from(at));
    }

    @Test
    void 처리_대기_사용자_신고가_있으면_판정만_저장하고_자동_신고는_만들지_않는다() {
        Instant now = Instant.now();
        Long author = user(false, false, false);
        Long reporter = user(false, false, false);
        Long harmful = post(author, HARMFUL + " " + UUID.randomUUID(), now.minus(Duration.ofHours(1)));
        jdbc.update("INSERT INTO content_reports (reporter_user_id, target_type, target_id, target_owner_user_id, "
                + "reason, status, created_at) VALUES (?, 'FEED_POST', ?, ?, 'SPAM', 'RECEIVED', ?)",
                reporter, harmful, author, Timestamp.from(now));

        trigger.screen();

        assertThat(screening("FEED_POST", harmful).get("flagged")).isEqualTo(true);
        assertThat(screening("FEED_POST", harmful).get("report_id")).isNull();
        assertThat(jdbc.queryForList("SELECT source FROM content_reports WHERE target_type = 'FEED_POST' "
                + "AND target_id = ?", String.class, harmful)).containsExactly("USER");
    }

    @Test
    void 글이_수정되면_다시_판정하고_대기_중이면_유지하다가_처리된_뒤의_수정은_새로_자동_신고한다() {
        Instant now = Instant.now();
        Long author = user(false, false, false);
        Long post = post(author, HARMFUL + " 처음 " + UUID.randomUUID(), now.minus(Duration.ofHours(1)));
        trigger.screen();
        Long firstReport = ((Number) screening("FEED_POST", post).get("report_id")).longValue();

        jdbc.update("UPDATE feed_posts SET content = ? WHERE id = ?", HARMFUL + " 수정1 " + UUID.randomUUID(), post);
        trigger.screen();
        assertThat(reportCount("FEED_POST", post)).isEqualTo(1);
        assertThat(screening("FEED_POST", post).get("content_hash")).isEqualTo(ContentScreeningTrigger.sha256(content(post)));

        // 관리자 DISMISS 와 같은 경로: 대상 단위로 대기 신고를 닫음(자동 신고 포함)
        int resolved = tx.execute(status -> reports.resolveReceived(ContentReportTargetType.FEED_POST, post,
                ContentReportStatus.DISMISSED, Instant.now(), null));
        assertThat(resolved).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM content_reports WHERE id = ?", String.class, firstReport))
                .isEqualTo("DISMISSED");

        jdbc.update("UPDATE feed_posts SET content = ? WHERE id = ?", HARMFUL + " 수정2 " + UUID.randomUUID(), post);
        trigger.screen();
        assertThat(reportCount("FEED_POST", post)).isEqualTo(2);
        Long secondReport = ((Number) screening("FEED_POST", post).get("report_id")).longValue();
        assertThat(secondReport).isNotEqualTo(firstReport);
        assertThat(jdbc.queryForObject("SELECT status FROM content_reports WHERE id = ?", String.class, secondReport))
                .isEqualTo("RECEIVED");
        verify(notifier, never()).notifyFailure(anyString(), anyString(), anyString());
    }

    @Test
    void 기존_사용자_신고는_USER로_보존되고_CHECK가_출처별_신고자_조합을_강제한다() {
        Long author = user(false, false, false);
        Long reporter = user(false, false, false);
        Long post = post(author, "체크용 " + UUID.randomUUID(), Instant.now().minus(Duration.ofDays(30)));
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO content_reports (reporter_user_id, target_type, target_id, reason, status, created_at) "
                + "VALUES (?, 'FEED_POST', ?, 'SPAM', 'RECEIVED', ?)", reporter, post, now);
        assertThat(jdbc.queryForObject("SELECT source FROM content_reports WHERE reporter_user_id = ?", String.class,
                reporter)).isEqualTo("USER");

        assertThatThrownBy(() -> jdbc.update("INSERT INTO content_reports (reporter_user_id, source, target_type, "
                + "target_id, reason, status, created_at) VALUES (NULL, 'USER', 'FEED_POST', ?, 'SPAM', 'RECEIVED', ?)",
                post, now)).isInstanceOf(DataAccessException.class).hasMessageContaining("ck_content_reports_source");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO content_reports (reporter_user_id, source, target_type, "
                + "target_id, reason, status, created_at) VALUES (?, 'AUTO', 'FEED_POST', ?, 'ABUSE', 'RECEIVED', ?)",
                author, post, now)).isInstanceOf(DataAccessException.class).hasMessageContaining("ck_content_reports_source");
    }

    private Long user(boolean ai, boolean bot, boolean deleted) {
        Timestamp created = Timestamp.from(Instant.now().minus(Duration.ofDays(40)));
        jdbc.update("""
                INSERT INTO users (nickname, created_at, updated_at, is_ai, is_bot, bot_key, deleted_at)
                VALUES ('판정 테스트', ?, ?, ?, ?, ?, ?)
                """, created, created, ai, bot, bot ? "screening-bot-" + System.nanoTime() : null,
                deleted ? Timestamp.from(Instant.now()) : null);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM users", Long.class);
        userIds.add(id);
        return id;
    }

    private Long post(Long author, String content, Instant createdAt) {
        jdbc.update("""
                INSERT INTO feed_posts (author_id, client_post_id, request_hash, content, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, author, UUID.randomUUID().toString(), "h".repeat(64), content, utc(createdAt),
                utc(createdAt));
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM feed_posts", Long.class);
        postIds.add(id);
        return id;
    }

    private Long comment(Long post, Long author, String content, Instant createdAt) {
        jdbc.update("""
                INSERT INTO feed_comments (post_id, author_id, client_comment_id, request_hash, content, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, post, author, UUID.randomUUID().toString(), "h".repeat(64), content, utc(createdAt));
        return jdbc.queryForObject("SELECT MAX(id) FROM feed_comments", Long.class);
    }

    // JPA(Hibernate)는 Instant 를 UTC 벽시계로 저장하고 후보 쿼리도 Instant 를 UTC 로 바인딩함 — 테스트 데이터도 같은 규칙으로 넣음.
    // Timestamp.from 은 JVM 기본 시간대로 저장돼 창 경계가 어긋남.
    private static Timestamp utc(Instant instant) {
        return Timestamp.valueOf(java.time.LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC));
    }

    private String content(Long post) {
        return jdbc.queryForObject("SELECT content FROM feed_posts WHERE id = ?", String.class, post);
    }

    private boolean screened(String type, Long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM content_screenings WHERE target_type = ? AND target_id = ?",
                Integer.class, type, id) > 0;
    }

    private Map<String, Object> screening(String type, Long id) {
        return jdbc.queryForMap("SELECT * FROM content_screenings WHERE target_type = ? AND target_id = ?", type, id);
    }

    private int reportCount(String type, Long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM content_reports WHERE target_type = ? AND target_id = ?",
                Integer.class, type, id);
    }
}
