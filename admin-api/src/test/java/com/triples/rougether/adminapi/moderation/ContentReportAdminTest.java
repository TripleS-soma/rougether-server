package com.triples.rougether.adminapi.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.feed.entity.FeedComment;
import com.triples.rougether.domain.feed.entity.FeedPost;
import com.triples.rougether.domain.feed.repository.FeedCommentRepository;
import com.triples.rougether.domain.feed.repository.FeedPostRepository;
import com.triples.rougether.domain.market.entity.MarketAsset;
import com.triples.rougether.domain.market.entity.MarketAssetStatus;
import com.triples.rougether.domain.market.repository.MarketAssetRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.moderation.entity.ContentReport;
import com.triples.rougether.domain.moderation.entity.ContentReportReason;
import com.triples.rougether.domain.moderation.entity.ContentReportStatus;
import com.triples.rougether.domain.moderation.entity.ContentReportTargetType;
import com.triples.rougether.domain.moderation.repository.ContentReportRepository;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;

// 어드민 신고 대기열·처리(#399). HIDE 는 게시물·댓글 삭제 경로와 종목 거래 정지를 타고, 같은 대상의 대기 신고를 함께 닫음.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ContentReportAdminTest {

    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired FeedPostRepository posts;
    @Autowired FeedCommentRepository comments;
    @Autowired MarketAssetRepository assets;
    @Autowired ThemeRepository themes;
    @Autowired ItemRepository items;
    @Autowired ContentReportRepository reports;
    @Autowired EntityManager em;
    @MockitoBean S3Client s3Client;

    private User owner;
    private User reporter;
    private User secondReporter;

    @BeforeEach
    void setUp() {
        owner = users.save(User.signUp("report-owner-" + UUID.randomUUID() + "@rougether.dev"));
        reporter = users.save(User.signUp("reporter-" + UUID.randomUUID() + "@rougether.dev"));
        secondReporter = users.save(User.signUp("reporter2-" + UUID.randomUUID() + "@rougether.dev"));
    }

    private FeedPost feedPost(String content) {
        return posts.save(FeedPost.create(owner, UUID.randomUUID().toString(), "h".repeat(64), content));
    }

    private ContentReport report(User by, ContentReportTargetType type, Long targetId) {
        return reports.save(ContentReport.receive(by.getId(), type, targetId, owner.getId(),
                ContentReportReason.ABUSE, "불쾌해요", NOW));
    }

    private MarketAsset asset() {
        Item item = items.save(new Item(themes.findByCode("photo_furniture").orElseThrow(), "furniture", "positioned",
                null, null, "고양이 소파", null, null, "furniture/photo/" + UUID.randomUUID() + ".png", false, false));
        return assets.save(MarketAsset.issue(item.getId(), owner.getId(), 3, NOW));
    }

    private void resolve(Long reportId, String action) throws Exception {
        mockMvc.perform(post("/admin/reports/{id}/resolve", reportId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"" + action + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 신고_페이지가_렌더링된다() throws Exception {
        mockMvc.perform(get("/content-reports"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Rougether Admin · 신고")));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 대기열은_상태별_최신순이고_대상_미리보기를_싣는다() throws Exception {
        FeedPost post = feedPost("신고된 본문");
        ContentReport older = report(reporter, ContentReportTargetType.FEED_POST, post.getId());
        ContentReport newer = report(secondReporter, ContentReportTargetType.FEED_POST, post.getId());

        mockMvc.perform(get("/admin/reports").param("status", "RECEIVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].reportId").value(newer.getId()))
                .andExpect(jsonPath("$.items[1].reportId").value(older.getId()))
                .andExpect(jsonPath("$.items[0].targetType").value("FEED_POST"))
                .andExpect(jsonPath("$.items[0].targetOwnerUserId").value(owner.getId()))
                .andExpect(jsonPath("$.items[0].target.text").value("신고된 본문"))
                .andExpect(jsonPath("$.items[0].target.removed").value(false))
                .andExpect(jsonPath("$.page").value(0));

        mockMvc.perform(get("/admin/reports").param("status", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_STATUS_INVALID"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 게시물_숨김은_삭제_경로를_타고_같은_대상_대기_신고를_함께_닫는다() throws Exception {
        FeedPost post = feedPost("숨길 본문");
        FeedComment comment = comments.save(FeedComment.create(post, reporter, UUID.randomUUID().toString(),
                "h".repeat(64), "댓글"));
        ContentReport first = report(reporter, ContentReportTargetType.FEED_POST, post.getId());
        ContentReport second = report(secondReporter, ContentReportTargetType.FEED_POST, post.getId());

        mockMvc.perform(post("/admin/reports/{id}/resolve", first.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"HIDE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIONED"))
                .andExpect(jsonPath("$.resolvedCount").value(2));

        em.clear();
        FeedPost hidden = posts.findById(post.getId()).orElseThrow();
        assertThat(hidden.getDeletedAt()).isNotNull();
        assertThat(hidden.getContent()).isEmpty();
        assertThat(comments.findById(comment.getId())).isEmpty();
        assertThat(reports.findById(second.getId()).orElseThrow().getStatus()).isEqualTo(ContentReportStatus.ACTIONED);
        assertThat(reports.findById(second.getId()).orElseThrow().getResolvedBy()).isNotNull();

        // 이미 처리된 신고는 다시 처리하지 않음
        mockMvc.perform(post("/admin/reports/{id}/resolve", second.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"DISMISS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPORT_ALREADY_RESOLVED"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 댓글_숨김은_댓글_삭제이고_종료는_대상을_건드리지_않는다() throws Exception {
        FeedPost post = feedPost("본문");
        FeedComment kept = comments.save(FeedComment.create(post, owner, UUID.randomUUID().toString(), "h".repeat(64), "유지"));
        FeedComment removed = comments.save(FeedComment.create(post, owner, UUID.randomUUID().toString(), "h".repeat(64), "숨김"));

        resolve(report(reporter, ContentReportTargetType.FEED_COMMENT, kept.getId()).getId(), "DISMISS");
        resolve(report(reporter, ContentReportTargetType.FEED_COMMENT, removed.getId()).getId(), "HIDE");

        em.clear();
        assertThat(comments.findById(kept.getId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(comments.findById(removed.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(posts.findById(post.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 거래소_종목_숨김은_거래_정지다() throws Exception {
        MarketAsset asset = asset();
        ContentReport report = report(reporter, ContentReportTargetType.MARKET_ASSET, asset.getId());

        mockMvc.perform(get("/admin/reports"))
                .andExpect(jsonPath("$.items[?(@.reportId == " + report.getId() + ")].target.text").value("고양이 소파"));
        resolve(report.getId(), "HIDE");

        em.clear();
        assertThat(assets.findById(asset.getId()).orElseThrow().getStatus()).isEqualTo(MarketAssetStatus.SUSPENDED);
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 처리값_검증과_없는_신고() throws Exception {
        ContentReport report = report(reporter, ContentReportTargetType.FEED_POST, feedPost("본문").getId());
        mockMvc.perform(post("/admin/reports/{id}/resolve", report.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"DELETE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_ACTION_INVALID"));
        mockMvc.perform(post("/admin/reports/{id}/resolve", report.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPORT_ACTION_INVALID"));
        mockMvc.perform(post("/admin/reports/{id}/resolve", 999_999L).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"HIDE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
        // CSRF 없는 처리 요청은 거절
        mockMvc.perform(post("/admin/reports/{id}/resolve", report.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"HIDE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 신고되지_않은_피드_사진은_열람할_수_없다() throws Exception {
        mockMvc.perform(get("/admin/reports/feed-images/{id}", 999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPORT_IMAGE_NOT_FOUND"));
    }
}
