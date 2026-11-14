package com.triples.rougether.adminapi.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.chat.entity.ChatImage;
import com.triples.rougether.domain.chat.entity.ChatMessage;
import com.triples.rougether.domain.chat.entity.ChatRoom;
import com.triples.rougether.domain.chat.repository.ChatImageRepository;
import com.triples.rougether.domain.chat.repository.ChatMessageRepository;
import com.triples.rougether.domain.chat.repository.ChatRoomRepository;
import com.triples.rougether.domain.feed.entity.FeedComment;
import com.triples.rougether.domain.feed.entity.FeedPost;
import com.triples.rougether.domain.feed.repository.FeedCommentRepository;
import com.triples.rougether.domain.feed.repository.FeedPostRepository;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.repository.HouseRepository;
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
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

// 어드민 신고 대기열·처리(#399). HIDE 는 게시물·댓글 삭제 경로와 종목 거래 정지를 타고, 같은 대상의 대기 신고를 함께 닫음.
// 전용 H2 DB 를 씀: 같은 JVM 에서 ItemRarityRollbackTest 가 ALTER TABLE 로 제약을 붙였다 떼면 공유 H2 의
// 다른 테이블 CHECK 제약(market_assets·content_reports)이 "Check constraint invalid" 로 깨지는 H2 문제를 피함.
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:rougether-admin-content-reports;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
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
    @Autowired HouseRepository houses;
    @Autowired ChatRoomRepository chatRooms;
    @Autowired ChatMessageRepository chatMessages;
    @Autowired ChatImageRepository chatImages;
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

    private ChatRoom chatRoom() {
        House house = houses.save(House.create(owner, "신고 집", null, null, 4,
                UUID.randomUUID().toString().substring(0, 8), NOW.plusSeconds(3600)));
        return chatRooms.save(ChatRoom.forHouse(house.getId()));
    }

    private ChatMessage chatMessage(String content) {
        return chatMessages.save(ChatMessage.send(chatRoom(), owner, UUID.randomUUID().toString(), content, NOW));
    }

    private ChatImage attachedPhoto(ChatMessage message) {
        return attachedPhoto(message, 0);
    }

    private ChatImage attachedPhoto(ChatMessage message, int sortOrder) {
        ChatImage image = ChatImage.reserve(message.getRoomId(), owner,
                "private/chat/" + UUID.randomUUID() + ".jpg", 48, 32, NOW, NOW.plusSeconds(3600));
        image.complete();
        image.attach(message.getId(), sortOrder);
        return chatImages.save(image);
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

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 채팅_메시지_숨김은_소프트_삭제이고_같은_대상_대기_신고를_함께_닫는다() throws Exception {
        ChatMessage message = chatMessage("신고된 채팅");
        ContentReport first = report(reporter, ContentReportTargetType.CHAT_MESSAGE, message.getId());
        ContentReport second = report(secondReporter, ContentReportTargetType.CHAT_MESSAGE, message.getId());

        resolve(first.getId(), "HIDE");
        em.flush();
        em.clear();

        ChatMessage hidden = chatMessages.findById(message.getId()).orElseThrow();
        assertThat(hidden.isDeleted()).isTrue();
        assertThat(hidden.getContent()).isEqualTo("신고된 채팅");
        assertThat(reports.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(ContentReportStatus.ACTIONED);
        assertThat(reports.findById(second.getId()).orElseThrow().getStatus()).isEqualTo(ContentReportStatus.ACTIONED);
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 채팅_신고_종료는_메시지를_건드리지_않는다() throws Exception {
        ChatMessage message = chatMessage("문제없는 채팅");
        ContentReport report = report(reporter, ContentReportTargetType.CHAT_MESSAGE, message.getId());

        resolve(report.getId(), "DISMISS");
        em.flush();
        em.clear();

        assertThat(chatMessages.findById(message.getId()).orElseThrow().isDeleted()).isFalse();
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 대기열은_채팅_본문과_사진_ID와_삭제_여부를_싣는다() throws Exception {
        ChatMessage message = chatMessage("사진을 보냈어요");
        ChatImage image = attachedPhoto(message);
        report(reporter, ContentReportTargetType.CHAT_MESSAGE, message.getId());

        mockMvc.perform(get("/admin/reports").param("status", "RECEIVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].targetType").value("CHAT_MESSAGE"))
                .andExpect(jsonPath("$.items[0].target.text").value("사진을 보냈어요"))
                .andExpect(jsonPath("$.items[0].target.chatImageId").value(image.getId()))
                .andExpect(jsonPath("$.items[0].target.removed").value(false));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 대기열은_여러_장_사진_메시지의_사진_ID를_보낸_순서대로_모두_싣는다() throws Exception {
        ChatMessage message = chatMessage("사진 3장을 보냈어요");
        // 저장 순서(id)와 보낸 순서(sort_order)를 다르게 둠.
        ChatImage third = attachedPhoto(message, 2);
        ChatImage first = attachedPhoto(message, 0);
        ChatImage second = attachedPhoto(message, 1);
        ChatMessage text = chatMessage("글만 있는 채팅");
        report(reporter, ContentReportTargetType.CHAT_MESSAGE, text.getId());
        report(reporter, ContentReportTargetType.CHAT_MESSAGE, message.getId());
        org.mockito.Mockito.when(s3Client.getObjectAsBytes(org.mockito.ArgumentMatchers.any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().contentType("image/jpeg").build(),
                        new byte[] {1}));

        mockMvc.perform(get("/admin/reports").param("status", "RECEIVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].targetId").value(message.getId()))
                .andExpect(jsonPath("$.items[0].target.chatImageId").value(first.getId()))
                .andExpect(jsonPath("$.items[0].target.chatImageIds.length()").value(3))
                .andExpect(jsonPath("$.items[0].target.chatImageIds[0]").value(first.getId()))
                .andExpect(jsonPath("$.items[0].target.chatImageIds[1]").value(second.getId()))
                .andExpect(jsonPath("$.items[0].target.chatImageIds[2]").value(third.getId()))
                .andExpect(jsonPath("$.items[1].target.chatImageId").doesNotExist())
                .andExpect(jsonPath("$.items[1].target.chatImageIds.length()").value(0));
        // 신고된 메시지의 사진은 장마다 열람할 수 있음.
        for (ChatImage image : java.util.List.of(first, second, third)) {
            mockMvc.perform(get("/admin/reports/chat-images/{id}", image.getId())).andExpect(status().isOk());
        }
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 신고된_메시지의_채팅_사진만_운영자가_볼_수_있다() throws Exception {
        ChatMessage reported = chatMessage("사진을 보냈어요");
        ChatImage image = attachedPhoto(reported);
        ChatImage unreported = attachedPhoto(chatMessage("사진을 보냈어요"));
        report(reporter, ContentReportTargetType.CHAT_MESSAGE, reported.getId());
        byte[] bytes = {1, 2, 3};
        org.mockito.Mockito.when(s3Client.getObjectAsBytes(org.mockito.ArgumentMatchers.any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().contentType("image/jpeg").build(), bytes));

        mockMvc.perform(get("/admin/reports/chat-images/{id}", image.getId()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes))
                .andExpect(content().contentType(MediaType.IMAGE_JPEG));
        mockMvc.perform(get("/admin/reports/chat-images/{id}", unreported.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPORT_IMAGE_NOT_FOUND"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 이미_삭제된_채팅_메시지의_숨김은_삭제_시각을_바꾸지_않고_신고만_닫는다() throws Exception {
        ChatMessage message = chatMessage("이미 삭제된 채팅");
        message.delete(NOW);
        chatMessages.saveAndFlush(message);
        ContentReport report = report(reporter, ContentReportTargetType.CHAT_MESSAGE, message.getId());

        resolve(report.getId(), "HIDE");
        em.flush();
        em.clear();

        assertThat(chatMessages.findById(message.getId()).orElseThrow().getDeletedAt()).isEqualTo(NOW);
        assertThat(reports.findById(report.getId()).orElseThrow().getStatus()).isEqualTo(ContentReportStatus.ACTIONED);
    }

    @Test
    void 로그인하지_않으면_채팅_사진을_볼_수_없다() throws Exception {
        mockMvc.perform(get("/admin/reports/chat-images/{id}", 1L))
                .andExpect(status().is3xxRedirection());
    }
}
