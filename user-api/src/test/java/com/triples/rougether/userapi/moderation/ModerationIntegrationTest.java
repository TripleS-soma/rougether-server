package com.triples.rougether.userapi.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.moderation.entity.ContentReportReason;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.feed.dto.FeedCommentRequest;
import com.triples.rougether.userapi.feed.dto.FeedCreateRequest;
import com.triples.rougether.userapi.feed.dto.FeedPostResponse;
import com.triples.rougether.userapi.feed.service.FeedCommandService;
import com.triples.rougether.userapi.feed.service.FeedImageTransactions;
import com.triples.rougether.userapi.feed.service.FeedQueryService;
import com.triples.rougether.userapi.global.security.MemberRole;
import com.triples.rougether.userapi.member.service.MemberWithdrawalService;
import com.triples.rougether.userapi.moderation.dto.ContentReportRequest;
import com.triples.rougether.userapi.moderation.service.ContentReportService;
import com.triples.rougether.userapi.moderation.service.UserBlockService;
import com.triples.rougether.userapi.notification.fcm.FcmPushExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 신고·차단(#399)을 실제 MySQL 에서 검증함. 피드 사진은 저장소 없이 예약·완료 트랜잭션으로 만듦.
@SpringBootTest
@AutoConfigureMockMvc
class ModerationIntegrationTest {

    @Autowired FeedCommandService commands;
    @Autowired FeedQueryService query;
    @Autowired FeedImageTransactions images;
    @Autowired ContentReportService reports;
    @Autowired UserBlockService blocks;
    @Autowired MemberWithdrawalService withdrawal;
    @Autowired UserRepository users;
    @Autowired ThemeRepository themes;
    @Autowired ItemRepository items;
    @Autowired TokenService tokens;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean FcmPushExecutor push;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> assetIds = new ArrayList<>();
    private final List<Long> itemIds = new ArrayList<>();

    @AfterEach
    void clean() {
        for (Long id : assetIds) {
            jdbc.update("delete from content_reports where target_type = 'MARKET_ASSET' and target_id = ?", id);
            jdbc.update("delete from market_assets where id = ?", id);
        }
        for (Long id : itemIds) {
            jdbc.update("delete from items where id = ?", id);
        }
        for (Long id : userIds) {
            jdbc.update("delete from content_reports where reporter_user_id = ? or target_owner_user_id = ?", id, id);
            jdbc.update("delete from user_blocks where blocker_user_id = ? or blocked_user_id = ?", id, id);
            jdbc.update("delete from notification where user_id = ?", id);
        }
        for (Long id : userIds) {
            jdbc.update("delete c from feed_comments c join feed_posts p on p.id = c.post_id where p.author_id = ? or c.author_id = ?", id, id);
            jdbc.update("delete l from feed_likes l join feed_posts p on p.id = l.post_id where p.author_id = ? or l.user_id = ?", id, id);
            jdbc.update("delete from feed_images where owner_id = ?", id);
            jdbc.update("delete from feed_posts where author_id = ?", id);
            jdbc.update("delete from user_daily_activity where user_id = ?", id);
        }
        for (Long id : userIds) {
            jdbc.update("delete from users where id = ?", id);
        }
    }

    Long user() {
        Long id = users.save(User.signUp()).getId();
        userIds.add(id);
        return id;
    }

    String auth(Long id) {
        return "Bearer " + tokens.issueAccessToken(id, MemberRole.NORMAL);
    }

    Long feedPost(Long owner) {
        var image = images.reserve(owner, 64, 64);
        images.complete(owner, image.id());
        return commands.create(owner, new FeedCreateRequest(UUID.randomUUID(), "공개 게시물", List.of(image.id())));
    }

    Long comment(Long author, Long postId) {
        return commands.comment(author, postId, new FeedCommentRequest(UUID.randomUUID(), "댓글")).commentId();
    }

    Long asset(Long creator) {
        Long itemId = items.save(new Item(themes.findByCode("photo_furniture").orElseThrow(), "furniture", "positioned",
                null, null, "고양이 소파", null, null, "furniture/photo/" + UUID.randomUUID() + ".png", false, false)).getId();
        itemIds.add(itemId);
        jdbc.update("insert into market_assets (item_id, creator_user_id, total_supply, unissued_quantity, status, "
                + "created_at, updated_at) values (?, ?, 3, 2, 'ACTIVE', now(6), now(6))", itemId, creator);
        Long id = jdbc.queryForObject("select id from market_assets where item_id = ?", Long.class, itemId);
        assetIds.add(id);
        return id;
    }

    static ContentReportRequest reason(ContentReportReason reason) {
        return new ContentReportRequest(reason, "  설명  ");
    }

    void error(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode().code()).isEqualTo(code));
    }

    List<Long> feedIds(Long viewer, Long authorId) {
        return query.list(viewer, authorId, null, 50).items().stream().map(FeedPostResponse::postId).toList();
    }

    @Test
    void 재신고는_처음_신고를_201로_돌려주고_사유는_처음_값을_유지한다() throws Exception {
        Long owner = user(), reporter = user(), postId = feedPost(owner);
        String body = "{\"reason\":\"ABUSE\",\"detail\":\"  욕설이 있어요  \"}";
        String path = "/api/v1/feed/posts/" + postId + "/reports";

        var first = mvc.perform(post(path).header("Authorization", auth(reporter))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("RECEIVED"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post(path).header("Authorization", auth(reporter))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SPAM\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportId").value(Long.parseLong(first.replaceAll(".*\"reportId\":(\\d+).*", "$1"))));

        var row = jdbc.queryForMap("select count(*) cnt, max(reason) reason, max(detail) detail, max(target_owner_user_id) owner "
                + "from content_reports where reporter_user_id = ?", reporter);
        assertThat(((Number) row.get("cnt")).intValue()).isEqualTo(1);
        assertThat(row.get("reason")).isEqualTo("ABUSE");
        assertThat(row.get("detail")).isEqualTo("욕설이 있어요");
        assertThat(((Number) row.get("owner")).longValue()).isEqualTo(owner);
    }

    @Test
    void 내_콘텐츠와_없는_대상은_신고할_수_없다() {
        Long owner = user(), reporter = user(), postId = feedPost(owner), commentId = comment(owner, postId);
        Long assetId = asset(owner);

        error(() -> reports.reportFeedPost(owner, postId, reason(ContentReportReason.SPAM)), "REPORT_SELF_TARGET");
        error(() -> reports.reportFeedComment(owner, postId, commentId, reason(ContentReportReason.SPAM)), "REPORT_SELF_TARGET");
        error(() -> reports.reportMarketAsset(owner, assetId, reason(ContentReportReason.SPAM)), "REPORT_SELF_TARGET");
        error(() -> reports.reportFeedPost(reporter, 999_999_999L, reason(ContentReportReason.SPAM)), "FEED_POST_NOT_FOUND");
        error(() -> reports.reportFeedComment(reporter, postId, 999_999_999L, reason(ContentReportReason.SPAM)),
                "FEED_COMMENT_NOT_FOUND");
        error(() -> reports.reportMarketAsset(reporter, 999_999_999L, reason(ContentReportReason.SPAM)),
                "MARKET_ASSET_NOT_FOUND");

        commands.deleteComment(owner, postId, commentId);
        error(() -> reports.reportFeedComment(reporter, postId, commentId, reason(ContentReportReason.SPAM)),
                "FEED_COMMENT_NOT_FOUND");
        assertThat(reports.reportMarketAsset(reporter, assetId, reason(ContentReportReason.COPYRIGHT)).status().name())
                .isEqualTo("RECEIVED");
        commands.delete(owner, postId);
        error(() -> reports.reportFeedPost(reporter, postId, reason(ContentReportReason.SPAM)), "FEED_POST_NOT_FOUND");
    }

    @Test
    void 차단하면_차단한_사람에게만_글_댓글_개수_알림이_사라지고_해제하면_돌아온다() {
        Long me = user(), blocked = user(), other = user();
        Long blockedPost = feedPost(blocked), myPost = feedPost(me);
        comment(blocked, myPost);
        comment(other, myPost);
        blocks.block(me, blocked);
        blocks.block(me, blocked); // 멱등

        assertThat(feedIds(me, null)).contains(myPost).doesNotContain(blockedPost);
        assertThat(feedIds(me, blocked)).isEmpty();
        error(() -> query.get(me, blockedPost), "FEED_POST_NOT_FOUND");
        error(() -> query.comments(me, blockedPost, null, 20), "FEED_POST_NOT_FOUND");
        error(() -> commands.like(me, blockedPost, true), "FEED_POST_NOT_FOUND");
        assertThat(query.comments(me, myPost, null, 20).items()).extracting(c -> c.author().userId())
                .containsExactly(other);
        assertThat(query.get(me, myPost).commentCount()).isEqualTo(1);

        // 한 방향: 차단당한 사람·제3자 화면은 그대로
        assertThat(feedIds(blocked, null)).contains(myPost, blockedPost);
        assertThat(query.get(other, myPost).commentCount()).isEqualTo(2);
        assertThat(query.comments(blocked, myPost, null, 20).items()).hasSize(2);

        // 차단한 상대의 새 댓글은 알림을 만들지 않음(차단 전 댓글 알림 2건만 남음)
        int before = jdbc.queryForObject("select count(*) from notification where user_id = ?", Integer.class, me);
        comment(blocked, myPost);
        assertThat(jdbc.queryForObject("select count(*) from notification where user_id = ?", Integer.class, me))
                .isEqualTo(before);
        comment(other, myPost);
        assertThat(jdbc.queryForObject("select count(*) from notification where user_id = ?", Integer.class, me))
                .isEqualTo(before + 1);

        // 차단해도 신고는 가능
        assertThat(reports.reportFeedPost(me, blockedPost, reason(ContentReportReason.ABUSE)).reportId()).isNotNull();

        blocks.unblock(me, blocked);
        blocks.unblock(me, blocked); // 멱등
        assertThat(feedIds(me, null)).contains(blockedPost);
        assertThat(query.get(me, myPost).commentCount()).isEqualTo(4);
    }

    @Test
    void 차단이_섞여도_커서_페이지는_크기를_채우고_중복_누락이_없다() {
        Long me = user(), friend = user(), blocked = user();
        List<Long> visible = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            visible.add(feedPost(friend));
            feedPost(blocked);
            feedPost(blocked);
        }
        blocks.block(me, blocked);

        var first = query.list(me, null, null, 2);
        assertThat(first.items()).extracting(FeedPostResponse::postId).containsExactly(visible.get(2), visible.get(1));
        assertThat(first.hasNext()).isTrue();
        var second = query.list(me, null, first.nextCursor(), 2);
        assertThat(second.items()).extracting(FeedPostResponse::postId).first().isEqualTo(visible.get(0));
        assertThat(second.items()).extracting(p -> p.author().userId()).doesNotContain(blocked);
    }

    @Test
    void 차단_API는_자기자신과_없는_회원을_거절하고_목록을_최근순_커서로_준다() throws Exception {
        Long me = user(), a = user(), b = user(), c = user();
        mvc.perform(put("/api/v1/users/" + me + "/block").header("Authorization", auth(me)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BLOCK_SELF"));
        mvc.perform(put("/api/v1/users/999999999/block").header("Authorization", auth(me)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        for (Long target : List.of(a, b, c)) {
            mvc.perform(put("/api/v1/users/" + target + "/block").header("Authorization", auth(me)))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(put("/api/v1/users/" + a + "/block").header("Authorization", auth(me)))
                .andExpect(status().isNoContent());

        var page = mvc.perform(get("/api/v1/me/blocks").param("size", "2").header("Authorization", auth(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].userId").value(c))
                .andExpect(jsonPath("$.items[1].userId").value(b))
                .andExpect(jsonPath("$.items[0].blockedAt").exists())
                .andExpect(jsonPath("$.hasNext").value(true))
                .andReturn().getResponse().getContentAsString();
        String cursor = page.replaceAll(".*\"nextCursor\":(\\d+).*", "$1");
        mvc.perform(get("/api/v1/me/blocks").param("size", "2").param("cursor", cursor).header("Authorization", auth(me)))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].userId").value(a))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));

        mvc.perform(delete("/api/v1/users/" + b + "/block").header("Authorization", auth(me)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/users/" + b + "/block").header("Authorization", auth(me)))
                .andExpect(status().isNoContent());
        assertThat(blocks.list(me, null, 20).items()).extracting(u -> u.userId()).containsExactly(c, a);
    }

    @Test
    void 탈퇴하면_양방향_차단과_탈퇴자가_한_신고를_지우고_탈퇴자_콘텐츠_신고는_남긴다() {
        Long leaver = user(), other = user(), third = user();
        Long leaverPost = feedPost(leaver), otherPost = feedPost(other);
        blocks.block(leaver, other);
        blocks.block(third, leaver);
        reports.reportFeedPost(leaver, otherPost, reason(ContentReportReason.SPAM));
        reports.reportFeedPost(other, leaverPost, reason(ContentReportReason.ABUSE));

        withdrawal.withdraw(leaver);

        assertThat(jdbc.queryForObject("select count(*) from user_blocks where blocker_user_id = ? or blocked_user_id = ?",
                Integer.class, leaver, leaver)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from content_reports where reporter_user_id = ?",
                Integer.class, leaver)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from content_reports where target_owner_user_id = ? "
                + "and status = 'RECEIVED'", Integer.class, leaver)).isEqualTo(1);
        // 탈퇴한 회원은 차단 대상이 될 수 없음
        error(() -> blocks.block(other, leaver), "USER_NOT_FOUND");
    }
}
