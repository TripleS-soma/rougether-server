package com.triples.rougether.userapi.feed;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.feed.dto.*;
import com.triples.rougether.userapi.feed.service.*;
import com.triples.rougether.userapi.global.security.MemberRole;
import com.triples.rougether.userapi.member.service.MemberWithdrawalService;
import com.triples.rougether.userapi.notification.dto.NotificationSettingUpdateRequest;
import com.triples.rougether.userapi.notification.fcm.FcmPushExecutor;
import com.triples.rougether.userapi.notification.service.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class FeedCommentNotificationTest {
    @Autowired FeedCommandService commands;
    @Autowired FeedImageTransactions images;
    @Autowired NotificationSettingService settings;
    @Autowired UserRepository users;
    @Autowired MemberWithdrawalService withdrawal;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenService tokens;
    @Autowired MockMvc mvc;
    @MockitoBean FcmPushExecutor push;
    @MockitoSpyBean NotificationService notifications;
    private final List<Long> userIds = new ArrayList<>();

    Long user() {
        Long id = users.save(User.signUp()).getId();
        userIds.add(id);
        return id;
    }
    Long post(Long owner) {
        var image = images.reserve(owner, 64, 64);
        images.complete(owner, image.id());
        return commands.create(owner, new FeedCreateRequest(UUID.randomUUID(), "공개 게시물", List.of(image.id())));
    }
    FeedCommentRequest request() { return new FeedCommentRequest(UUID.randomUUID(), "댓글 원문은 알림 사본으로 남기지 않음"); }
    List<Map<String, Object>> inbox(Long owner) {
        return jdbc.queryForList("select id, type, title, body, ref_id, push_status from notification where user_id=?", owner);
    }
    @AfterEach void clean() {
        for (Long id : userIds) {
            jdbc.update("delete from notification where user_id=?", id);
            jdbc.update("delete from notification_setting where user_id=?", id);
        }
        for (Long id : userIds) {
            jdbc.update("delete c from feed_comments c join feed_posts p on p.id=c.post_id where p.author_id=? or c.author_id=?", id, id);
            jdbc.update("delete from feed_images where owner_id=?", id);
            jdbc.update("delete from feed_posts where author_id=?", id);
            jdbc.update("delete from user_daily_activity where user_id=?", id);
            jdbc.update("delete from users where id=?", id);
        }
    }

    @Test void 댓글_커밋후_작성자에게_한번만_알리고_알림함과_push에_게시물_ID를_싣는다() throws Exception {
        Long owner = user(), commenter = user(), id = post(owner);
        var request = request();
        var first = commands.comment(commenter, id, request);
        assertThat(commands.comment(commenter, id, request).commentId()).isEqualTo(first.commentId());
        assertThat(inbox(commenter)).isEmpty();
        var row = inbox(owner).getFirst();
        assertThat(inbox(owner)).hasSize(1);
        assertThat(row.get("type")).isEqualTo("FEED_COMMENT");
        assertThat(((Number) row.get("ref_id")).longValue()).isEqualTo(id);
        assertThat(row.get("body")).isEqualTo("내 게시물에 새 댓글이 달렸어요. 확인해 보세요!");
        Long notificationId = ((Number) row.get("id")).longValue();
        verify(push).push(notificationId, owner, "새 댓글이 달렸어요", row.get("body").toString(),
                Map.of("type", "FEED_COMMENT", "notificationId", notificationId.toString(), "postId", id.toString()));
        verifyNoMoreInteractions(push);
        mvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + tokens.issueAccessToken(owner, MemberRole.NORMAL)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].type").value("FEED_COMMENT"))
                .andExpect(jsonPath("$.items[0].refId").value(id));
    }

    @Test void 본인댓글과_잘못된_재시도는_알림을_만들지_않는다() {
        Long owner = user(), commenter = user(), id = post(owner);
        commands.comment(owner, id, request());
        assertThat(inbox(owner)).isEmpty();
        verifyNoInteractions(push);
        var request = request();
        var comment = commands.comment(commenter, id, request);
        assertThatThrownBy(() -> commands.comment(commenter, id, new FeedCommentRequest(request.clientCommentId(), "다른 댓글")))
                .isInstanceOf(BusinessException.class);
        commands.deleteComment(commenter, id, comment.commentId());
        assertThatThrownBy(() -> commands.comment(commenter, id, request)).isInstanceOf(BusinessException.class);
        assertThat(inbox(owner)).hasSize(1);
    }

    @Test void 트랜잭션_롤백은_댓글과_알림을_함께_없애고_push하지_않는다() {
        Long owner = user(), commenter = user(), id = post(owner);
        var request = request();
        tx.executeWithoutResult(status -> {
            commands.comment(commenter, id, request);
            assertThat(inbox(owner)).hasSize(1);
            verifyNoInteractions(push);
            status.setRollbackOnly();
        });
        assertThat(inbox(owner)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from feed_comments where post_id=?", Long.class, id)).isZero();
        verifyNoInteractions(push);
        commands.comment(commenter, id, request);
        assertThat(inbox(owner)).hasSize(1);
    }

    @Test void 알림저장_실패는_댓글도_롤백한다() {
        Long owner = user(), commenter = user(), id = post(owner);
        doThrow(new IllegalStateException("notification unavailable")).when(notifications).send(eq(owner), any(), eq(id));
        assertThatThrownBy(() -> commands.comment(commenter, id, request())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from feed_comments where post_id=?", Long.class, id)).isZero();
        assertThat(inbox(owner)).isEmpty();
        verifyNoInteractions(push);
    }

    @Test void feed_설정만_변경할수있고_off여도_알림내역은_남는다() throws Exception {
        Long owner = user(), commenter = user(), id = post(owner);
        String auth = "Bearer " + tokens.issueAccessToken(owner, MemberRole.NORMAL);
        mvc.perform(patch("/api/v1/users/me/notification-settings").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"feed\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.feed").value(false))
                .andExpect(jsonPath("$.all").value(true)).andExpect(jsonPath("$.house").value(true));
        mvc.perform(patch("/api/v1/users/me/notification-settings").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"house\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.feed").value(false));
        commands.comment(commenter, id, request());
        assertThat(inbox(owner)).singleElement().satisfies(r -> assertThat(r.get("push_status")).isEqualTo("BLOCKED"));
        verifyNoInteractions(push);
    }

    @Test void 마스터_off는_feed_on보다_우선하고_집알림_off는_피드를_막지_않는다() {
        Long owner = user(), commenter = user(), id = post(owner);
        settings.updateSettings(owner, new NotificationSettingUpdateRequest(false, null, null, true));
        commands.comment(commenter, id, request());
        assertThat(inbox(owner).getFirst().get("push_status")).isEqualTo("BLOCKED");
        verifyNoInteractions(push);
        settings.updateSettings(owner, new NotificationSettingUpdateRequest(true, false, false, null));
        commands.comment(commenter, id, request());
        verify(push).push(anyLong(), eq(owner), anyString(), anyString(), anyMap());
        assertThat(inbox(owner)).hasSize(2);
    }

    @Test void 알림은_댓글작성자가_아닌_게시물작성자의_언어를_따른다() {
        Long owner = user(), commenter = user(), id = post(owner);
        tx.executeWithoutResult(s -> users.findById(owner).orElseThrow().changePreferences("en", null));
        commands.comment(commenter, id, request());
        var row = inbox(owner).getFirst();
        assertThat(row.get("title")).isEqualTo("New comment");
        assertThat(row.get("body")).isEqualTo("Someone commented on your post. Take a look!");
        verify(push).push(anyLong(), eq(owner), eq("New comment"), eq(row.get("body").toString()), anyMap());
    }

    @Test void 동시에_서로의_글에_댓글을_달아도_교착이나_중복알림이_없다() throws Exception {
        Long a = user(), b = user(), ap = post(a), bp = post(b);
        var ar = request(); var br = request();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> work = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                boolean fromA = i % 2 == 0;
                work.add(pool.submit(() -> { start.await();
                    return commands.comment(fromA ? a : b, fromA ? bp : ap, fromA ? ar : br); }));
            }
            start.countDown();
            for (var task : work) task.get(20, TimeUnit.SECONDS);
        }
        assertThat(inbox(a)).hasSize(1);
        assertThat(inbox(b)).hasSize(1);
        verify(push, times(2)).push(anyLong(), anyLong(), anyString(), anyString(), anyMap());
    }

    @Test void 삭제된글과_탈퇴한수신자에게는_댓글과_알림을_만들지_않는다() {
        Long owner = user(), commenter = user(), id = post(owner), second = post(owner);
        commands.delete(owner, id);
        assertThatThrownBy(() -> commands.comment(commenter, id, request())).isInstanceOf(BusinessException.class);
        withdrawal.withdraw(owner);
        assertThatThrownBy(() -> commands.comment(commenter, second, request())).isInstanceOf(BusinessException.class);
        assertThat(inbox(owner)).isEmpty();
        verifyNoInteractions(push);
    }

    @Test void push제출_실패는_커밋된_댓글을_실패로_되돌리지_않는다() {
        Long owner = user(), commenter = user(), id = post(owner);
        doThrow(new org.springframework.core.task.TaskRejectedException("full"))
                .when(push).push(anyLong(), anyLong(), anyString(), anyString(), anyMap());
        assertThatCode(() -> commands.comment(commenter, id, request())).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("select count(*) from feed_comments where post_id=?", Long.class, id)).isEqualTo(1);
        assertThat(inbox(owner)).hasSize(1);
    }
}
