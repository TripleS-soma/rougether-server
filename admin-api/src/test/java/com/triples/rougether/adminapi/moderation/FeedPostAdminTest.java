package com.triples.rougether.adminapi.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.feed.entity.FeedPost;
import com.triples.rougether.domain.feed.repository.FeedPostRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.moderation.entity.ContentReport;
import com.triples.rougether.domain.moderation.entity.ContentReportReason;
import com.triples.rougether.domain.moderation.entity.ContentReportStatus;
import com.triples.rougether.domain.moderation.entity.ContentReportTargetType;
import com.triples.rougether.domain.moderation.repository.ContentReportRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;

// 어드민 피드 관리(#534). 신고 없이도 글을 보고 지울 수 있고, 삭제는 작성자 삭제와 같은 tombstone 경로를 탐.
// 전용 H2 DB 를 씀(ContentReportAdminTest 와 같은 이유 — 공유 H2 의 CHECK 제약 문제 회피).
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:rougether-admin-feed-posts;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@AutoConfigureMockMvc
@Transactional
class FeedPostAdminTest {

    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired FeedPostRepository posts;
    @Autowired ContentReportRepository reports;
    @Autowired EntityManager em;
    @MockitoBean S3Client s3Client;

    private User author;
    private User reporter;

    @BeforeEach
    void setUp() {
        author = users.save(User.signUp("feed-author-" + UUID.randomUUID() + "@rougether.dev"));
        reporter = users.save(User.signUp("feed-reporter-" + UUID.randomUUID() + "@rougether.dev"));
    }

    private FeedPost feedPost(String content) {
        return posts.save(FeedPost.create(author, UUID.randomUUID().toString(), "h".repeat(64), content));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 피드_관리_페이지가_렌더링된다() throws Exception {
        mockMvc.perform(get("/feed-posts"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Rougether Admin · 피드")));
    }

    @Test
    void 로그인하지_않으면_목록과_삭제를_쓸_수_없다() throws Exception {
        FeedPost post = feedPost("본문");

        mockMvc.perform(get("/admin/feed-posts")).andExpect(status().is3xxRedirection());
        mockMvc.perform(delete("/admin/feed-posts/{id}", post.getId()).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(posts.findById(post.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 목록은_최신순이고_삭제된_글은_요청할_때만_보인다() throws Exception {
        FeedPost older = feedPost("먼저 쓴 글");
        FeedPost newer = feedPost("나중에 쓴 글");
        FeedPost removed = feedPost("지운 글");
        removed.delete(NOW);
        em.flush();

        mockMvc.perform(get("/admin/feed-posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].postId").value(newer.getId()))
                .andExpect(jsonPath("$.items[0].text").value("나중에 쓴 글"))
                .andExpect(jsonPath("$.items[0].authorUserId").value(author.getId()))
                .andExpect(jsonPath("$.items[1].postId").value(older.getId()))
                .andExpect(jsonPath("$.items[?(@.postId == " + removed.getId() + ")]").isEmpty());

        mockMvc.perform(get("/admin/feed-posts").param("includeDeleted", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].postId").value(removed.getId()))
                .andExpect(jsonPath("$.items[0].deletedAt").isNotEmpty());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 삭제하면_본문이_지워지고_그_글의_대기_신고가_닫힌다() throws Exception {
        FeedPost post = feedPost("지워야 하는 글");
        ContentReport report = reports.save(ContentReport.receive(reporter.getId(), ContentReportTargetType.FEED_POST,
                post.getId(), author.getId(), ContentReportReason.ABUSE, "불쾌해요", NOW));

        mockMvc.perform(delete("/admin/feed-posts/{id}", post.getId()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postId").value(post.getId()))
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.resolvedReports").value(1));

        em.flush();
        em.clear();
        FeedPost after = posts.findById(post.getId()).orElseThrow();
        assertThat(after.getDeletedAt()).isNotNull();
        assertThat(after.getContent()).isEmpty();
        assertThat(reports.findById(report.getId()).orElseThrow().getStatus()).isEqualTo(ContentReportStatus.ACTIONED);
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 이미_삭제된_글을_다시_지워도_성공하고_없는_글은_404다() throws Exception {
        FeedPost post = feedPost("한 번 지운 글");
        mockMvc.perform(delete("/admin/feed-posts/{id}", post.getId()).with(csrf())).andExpect(status().isOk());

        mockMvc.perform(delete("/admin/feed-posts/{id}", post.getId()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(false));
        mockMvc.perform(delete("/admin/feed-posts/{id}", 999_999_999L).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FEED_POST_NOT_FOUND"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void CSRF_토큰이_없으면_삭제를_거부한다() throws Exception {
        FeedPost post = feedPost("본문");

        mockMvc.perform(delete("/admin/feed-posts/{id}", post.getId())).andExpect(status().isForbidden());
        assertThat(posts.findById(post.getId()).orElseThrow().getDeletedAt()).isNull();
    }
}
