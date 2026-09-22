package com.triples.rougether.userapi.feed;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.feed.repository.*;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.feed.dto.*;
import com.triples.rougether.userapi.feed.service.*;
import com.triples.rougether.userapi.global.security.MemberRole;
import com.triples.rougether.userapi.member.service.MemberWithdrawalService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class FeedIntegrationTest {
    @Autowired FeedCommandService commands;
    @Autowired FeedQueryService query;
    @Autowired FeedImageService images;
    @Autowired FeedImageTransactions imageTransactions;
    @Autowired FeedCleanupTransactions cleanup;
    @Autowired FeedPostRepository posts;
    @Autowired FeedImageRepository imageRows;
    @Autowired UserRepository users;
    @Autowired MemberWithdrawalService withdrawal;
    @Autowired TransactionTemplate tx;
    @Autowired TokenService tokens;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.triples.rougether.domain.moderation.repository.BannedWordRepository bannedWords;
    @Autowired com.triples.rougether.userapi.global.text.BannedWordChecker bannedChecker;
    @MockitoBean FeedImageStorage storage;
    @MockitoBean com.triples.rougether.userapi.notification.fcm.FcmPushExecutor push;
    private final List<Long> createdUsers = new ArrayList<>();
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @BeforeEach void storage() {
        doAnswer(i -> { objects.put(i.getArgument(0), i.getArgument(1)); return null; }).when(storage).put(anyString(), any());
        when(storage.read(anyString())).thenAnswer(i -> objects.get(i.getArgument(0)));
        doAnswer(i -> { objects.remove(i.getArgument(0)); return null; }).when(storage).delete(anyString());
    }
    @AfterEach void clean() {
        tx.executeWithoutResult(s -> {
            for (Long id : createdUsers) {
                jdbc.update("delete from notification where user_id=?", id);
                jdbc.update("delete c from feed_comments c join feed_posts p on p.id=c.post_id where p.author_id=? or c.author_id=?", id, id);
                jdbc.update("delete l from feed_likes l join feed_posts p on p.id=l.post_id where p.author_id=? or l.user_id=?", id, id);
                jdbc.update("delete from feed_images where owner_id=?", id);
                jdbc.update("delete from feed_posts where author_id=?", id);
                jdbc.update("delete from user_daily_activity where user_id=?", id);
                jdbc.update("delete from users where id=?", id);
            }
        });
    }
    Long user() {
        Long id = tx.execute(s -> users.save(User.signUp()).getId());
        createdUsers.add(id); return id;
    }
    String auth(Long id) { return "Bearer " + tokens.issueAccessToken(id, MemberRole.NORMAL); }
    static MockMultipartFile photo() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(48, 32, BufferedImage.TYPE_INT_RGB), "png", out);
        return new MockMultipartFile("file", "photo.png", "image/png", out.toByteArray());
    }
    FeedCreateRequest request(Long owner, String text) throws Exception {
        return new FeedCreateRequest(UUID.randomUUID(), text, List.of(images.upload(owner, photo()).imageId()));
    }
    Long createPost(Long owner) throws Exception { return commands.create(owner, request(owner, "오늘의 기록")); }
    void error(Runnable runnable, String code) {
        assertThatThrownBy(runnable::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode().code()).isEqualTo(code));
    }

    @Test void HTTP_사진업로드부터_공개피드_좋아요_댓글_수정_삭제까지_동작한다() throws Exception {
        Long owner = user(), viewer = user();
        var upload = mvc.perform(multipart("/api/v1/feed/images").file(photo()).header("Authorization", auth(owner)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.contentType").value("image/jpeg"))
                .andReturn().getResponse();
        Long imageId = mapper.readTree(upload.getContentAsString()).path("imageId").asLong();
        mvc.perform(get("/api/v1/feed/images/" + imageId).header("Authorization", auth(viewer))).andExpect(status().isNotFound());
        var req = new FeedCreateRequest(UUID.randomUUID(), "일상과 루틴 인증", List.of(imageId));
        var created = mvc.perform(post("/api/v1/feed/posts").header("Authorization", auth(owner))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.mine").value(true))
                .andExpect(jsonPath("$.author.userId").value(owner)).andReturn().getResponse();
        long id = mapper.readTree(created.getContentAsString()).path("postId").asLong();
        String path = "/api/v1/feed/posts/" + id;
        mvc.perform(get("/api/v1/feed/posts").param("authorId", owner.toString()).header("Authorization", auth(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].postId").value(id))
                .andExpect(jsonPath("$.items[0].mine").value(false)).andExpect(jsonPath("$.items[0].author.email").doesNotExist());
        mvc.perform(get("/api/v1/feed/images/" + imageId).header("Authorization", auth(viewer)))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        for (int i=0; i<2; i++) mvc.perform(put(path + "/like").header("Authorization", auth(viewer))).andExpect(status().isNoContent());
        mvc.perform(get(path).header("Authorization", auth(viewer))).andExpect(jsonPath("$.likeCount").value(1))
                .andExpect(jsonPath("$.likedByMe").value(true));
        var comment = mvc.perform(post(path + "/comments").header("Authorization", auth(viewer))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(new FeedCommentRequest(UUID.randomUUID(), "멋져요"))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        long commentId = mapper.readTree(comment.getContentAsString()).path("commentId").asLong();
        mvc.perform(delete(path + "/comments/" + commentId).header("Authorization", auth(owner))).andExpect(status().isForbidden());
        mvc.perform(delete(path + "/comments/" + commentId).header("Authorization", auth(viewer))).andExpect(status().isNoContent());
        mvc.perform(patch(path).header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"수정한 글\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.content").value("수정한 글"));
        mvc.perform(delete(path + "/like").header("Authorization", auth(viewer))).andExpect(status().isNoContent());
        mvc.perform(delete(path).header("Authorization", auth(owner))).andExpect(status().isNoContent());
        mvc.perform(get(path).header("Authorization", auth(viewer))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/feed/images/" + imageId).header("Authorization", auth(owner))).andExpect(status().isNotFound());
        cleanup.image(imageId);
        assertThat(imageRows.findById(imageId)).isEmpty();
        assertThat(objects).isEmpty();
    }

    @Test void 사진순서와_재시도_충돌을_보존하고_타인의_사진은_연결하지_못한다() throws Exception {
        Long owner = user(), other = user();
        Long a = images.upload(owner, photo()).imageId(), b = images.upload(owner, photo()).imageId();
        var req = new FeedCreateRequest(UUID.randomUUID(), "순서", List.of(b, a));
        Long id = commands.create(owner, req);
        assertThat(commands.create(owner, req)).isEqualTo(id);
        assertThat(query.get(other, id).images()).extracting(FeedImageResponse::imageId).containsExactly(b, a);
        error(() -> commands.create(owner, new FeedCreateRequest(req.clientPostId(), "다른 본문", req.imageIds())), "FEED_REQUEST_CONFLICT");
        Long foreign = images.upload(other, photo()).imageId();
        error(() -> commands.create(owner, new FeedCreateRequest(UUID.randomUUID(), "", List.of(foreign))), "FEED_IMAGE_UNAVAILABLE");
        error(() -> commands.create(owner, new FeedCreateRequest(UUID.randomUUID(), "", List.of(a))), "FEED_IMAGE_UNAVAILABLE");
        commands.delete(owner, id);
        error(() -> commands.create(owner, req), "FEED_REQUEST_CONFLICT");
    }

    @Test void 새글과_삭제가_끼어도_페이지는_중복없이_이어지고_댓글은_오래된_순이다() throws Exception {
        Long owner = user(), viewer = user();
        Long first = createPost(owner), second = createPost(owner), third = createPost(owner);
        var page = query.list(viewer, owner, null, 2);
        assertThat(page.items()).extracting(FeedPostResponse::postId).containsExactly(third, second);
        createPost(owner); commands.delete(owner, second);
        assertThat(query.list(viewer, owner, page.nextCursor(), 2).items()).extracting(FeedPostResponse::postId).containsExactly(first);
        List<Long> commentIds = new ArrayList<>();
        for (int i=0;i<3;i++) commentIds.add(commands.comment(viewer, third, new FeedCommentRequest(UUID.randomUUID(), "댓글"+i)).commentId());
        var cpage = query.comments(owner, third, null, 2);
        assertThat(cpage.items()).extracting(FeedCommentResponse::commentId).containsExactly(commentIds.get(0), commentIds.get(1));
        assertThat(query.comments(owner, third, cpage.nextCursor(), 2).items()).extracting(FeedCommentResponse::commentId).containsExactly(commentIds.get(2));
    }

    @Test void 댓글재시도는_중복되지_않고_삭제된댓글과_다른본문의_재사용은_거절한다() throws Exception {
        Long owner = user(), viewer = user(); Long id = createPost(owner);
        var req = new FeedCommentRequest(UUID.randomUUID(), "댓글");
        var comment = commands.comment(viewer, id, req);
        assertThat(commands.comment(viewer, id, req).commentId()).isEqualTo(comment.commentId());
        error(() -> commands.comment(viewer, id, new FeedCommentRequest(req.clientCommentId(), "수정")), "FEED_REQUEST_CONFLICT");
        assertThat(query.get(owner, id).commentCount()).isEqualTo(1);
        commands.deleteComment(viewer, id, comment.commentId());
        assertThat(query.get(owner, id).commentCount()).isZero();
        error(() -> commands.comment(viewer, id, req), "FEED_REQUEST_CONFLICT");
    }

    @Test void 삭제와_수정은_작성자만_가능하고_삭제후_모든_반응경로가_닫힌다() throws Exception {
        Long owner = user(), viewer = user(); Long id = createPost(owner);
        error(() -> commands.update(viewer, id, "위조"), "FEED_FORBIDDEN");
        error(() -> commands.delete(viewer, id), "FEED_FORBIDDEN");
        commands.delete(owner, id); commands.delete(owner, id);
        error(() -> commands.like(viewer, id, true), "FEED_POST_NOT_FOUND");
        error(() -> commands.comment(viewer, id, new FeedCommentRequest(UUID.randomUUID(), "늦은 댓글")), "FEED_POST_NOT_FOUND");
        error(() -> query.comments(viewer, id, null, 20), "FEED_POST_NOT_FOUND");
    }

    @Test void 동시등록_좋아요_댓글_재시도도_각각_하나만_남는다() throws Exception {
        Long owner = user(), viewer = user(); var req = request(owner, "동시 등록");
        List<Long> ids = concurrent(6, () -> commands.create(owner, req));
        assertThat(new HashSet<>(ids)).hasSize(1); Long id=ids.getFirst();
        concurrent(8, () -> { commands.like(viewer, id, true); return true; });
        assertThat(query.get(viewer, id).likeCount()).isEqualTo(1);
        var comment = new FeedCommentRequest(UUID.randomUUID(), "동시 댓글");
        assertThat(new HashSet<>(concurrent(6, () -> commands.comment(viewer, id, comment).commentId()))).hasSize(1);
        concurrent(8, () -> { commands.like(viewer, id, false); return true; });
        assertThat(query.get(viewer, id).likeCount()).isZero();
    }

    @Test void 게시물삭제와_댓글_좋아요가_경합해도_삭제후_잔여반응이_없다() throws Exception {
        Long owner = user(), viewer = user(); Long id=createPost(owner);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(3)) {
            var deletion = pool.submit(() -> { start.await(); commands.delete(owner, id); return true; });
            var reaction = pool.submit(() -> { start.await(); try { commands.like(viewer, id, true); }
                catch (BusinessException e) { assertThat(e.getErrorCode().code()).isEqualTo("FEED_POST_NOT_FOUND"); } return true; });
            var comment = pool.submit(() -> { start.await(); try { commands.comment(viewer, id, new FeedCommentRequest(UUID.randomUUID(), "경합")); }
                catch (BusinessException e) { assertThat(e.getErrorCode().code()).isEqualTo("FEED_POST_NOT_FOUND"); } return true; });
            start.countDown(); deletion.get(20, TimeUnit.SECONDS); reaction.get(20, TimeUnit.SECONDS); comment.get(20, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("select count(*) from feed_likes where post_id=?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from feed_comments where post_id=?", Long.class, id)).isZero();
    }

    @Test void 실제회원탈퇴는_글_댓글_좋아요_사진을_즉시숨기고_잔여토큰을_거절한다() throws Exception {
        Long owner = user(), viewer = user(); var req=request(owner, "탈퇴할 글"); Long id=commands.create(owner, req);
        Long otherPost=createPost(viewer);
        commands.like(owner, otherPost, true);
        commands.comment(owner, otherPost, new FeedCommentRequest(UUID.randomUUID(), "탈퇴할 댓글"));
        withdrawal.withdraw(owner);
        assertThat(query.list(viewer, owner, null, 20).items()).isEmpty();
        error(() -> query.get(viewer, id), "FEED_POST_NOT_FOUND");
        error(() -> images.read(viewer, req.imageIds().getFirst()), "FEED_IMAGE_NOT_FOUND");
        assertThat(query.get(viewer, otherPost).likeCount()).isZero();
        assertThat(query.get(viewer, otherPost).commentCount()).isZero();
        mvc.perform(get("/api/v1/feed/posts").header("Authorization", auth(owner))).andExpect(status().isUnauthorized());
        error(() -> commands.like(owner, otherPost, true), "AUTH_INVALID_TOKEN");
        error(() -> commands.create(owner, req), "AUTH_INVALID_TOKEN");
        cleanup.withdrawnContent(); cleanup.image(req.imageIds().getFirst());
        assertThat(posts.findById(id).orElseThrow().getContent()).isEmpty();
        assertThat(imageRows.findById(req.imageIds().getFirst())).isEmpty();
    }

    @Test void 사진만료_취소_정리실패_재시도를_처리하고_게시된_사진은_만료시키지_않는다() throws Exception {
        Long owner = user(); var a=images.upload(owner, photo()); var b=images.upload(owner, photo());
        Long published=commands.create(owner, new FeedCreateRequest(UUID.randomUUID(), "게시됨", List.of(b.imageId())));
        tx.executeWithoutResult(s -> {
            imageRows.findById(a.imageId()).orElseThrow().expire(Instant.now().minusSeconds(1));
            imageRows.findById(b.imageId()).orElseThrow().expire(Instant.now().minusSeconds(1));
        });
        error(() -> commands.create(owner, new FeedCreateRequest(UUID.randomUUID(), "만료", List.of(a.imageId()))), "FEED_IMAGE_UNAVAILABLE");
        assertThat(imageRows.findCleanupCandidates(0, Instant.now(), org.springframework.data.domain.PageRequest.of(0, 100)))
                .contains(a.imageId()).doesNotContain(b.imageId());
        doThrow(new RuntimeException("temporary storage failure")).doNothing().when(storage).delete(a.storageKey());
        assertThatThrownBy(() -> cleanup.image(a.imageId())).isInstanceOf(RuntimeException.class);
        assertThat(imageRows.findById(a.imageId())).isPresent();
        cleanup.image(a.imageId()); cleanup.image(b.imageId());
        assertThat(imageRows.findById(a.imageId())).isEmpty();
        assertThat(imageRows.findById(b.imageId())).isPresent();
        assertThat(query.get(owner, published).images()).hasSize(1);
        var c=images.upload(owner, photo()); images.cancel(owner, c.imageId());
        error(() -> images.read(owner, c.imageId()), "FEED_IMAGE_NOT_FOUND"); cleanup.image(c.imageId());
        assertThat(imageRows.findById(c.imageId())).isEmpty();
    }

    @Test void 전송실패한_사진도_정리할_key가_남고_미사용_업로드를_제한한다() throws Exception {
        Long owner=user(); doThrow(new RuntimeException("storage down")).when(storage).put(anyString(), any());
        assertThatThrownBy(() -> images.upload(owner, photo())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode().code()).isEqualTo("FEED_STORAGE_UNAVAILABLE"));
        assertThat(imageRows.countByOwnerIdAndPostIsNull(owner)).isEqualTo(1);
        for (int i=0;i<29;i++) imageTransactions.reserve(owner, 30, 30);
        error(() -> imageTransactions.reserve(owner, 30, 30), "FEED_UPLOAD_LIMIT");
    }

    @Test void 인증과_본문_사진수_커서_입력검증을_적용한다() throws Exception {
        Long owner=user(); Long id=createPost(owner); String auth=auth(owner);
        mvc.perform(get("/api/v1/feed/posts")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/feed/posts").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientPostId\":\"bad\",\"imageIds\":[1]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/feed/posts").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new FeedCreateRequest(UUID.randomUUID(), "", List.of())))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/feed/posts?size=51").header("Authorization", auth)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/feed/posts?cursor=-1").header("Authorization", auth)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/feed/posts/"+id+"/comments").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new FeedCommentRequest(UUID.randomUUID(), " ")))).andExpect(status().isBadRequest());
        error(() -> commands.update(owner, id, "x".repeat(2001)), "FEED_INPUT_INVALID");
    }

    @Test void 금칙어는_등록_수정_댓글에_적용하고_봇은_피드에_접근하지_못한다() throws Exception {
        Long owner = user(); Long id = createPost(owner);
        var request = request(owner, "b@annedfeedword");
        var word = bannedWords.save(com.triples.rougether.domain.moderation.entity.BannedWord.of("bannedfeedword"));
        org.springframework.test.util.ReflectionTestUtils.setField(bannedChecker, "cacheLoadedAt", Instant.EPOCH);
        try {
            error(() -> commands.create(owner, request), "FEED_CONTENT_BANNED");
            error(() -> commands.update(owner, id, "bannedfeedword"), "FEED_CONTENT_BANNED");
            error(() -> commands.comment(owner, id, new FeedCommentRequest(UUID.randomUUID(), "bannedfeedword")), "FEED_CONTENT_BANNED");
            assertThat(query.get(owner, id).content()).isEqualTo("오늘의 기록");
        } finally {
            bannedWords.deleteById(word.getId());
            org.springframework.test.util.ReflectionTestUtils.setField(bannedChecker, "cacheLoadedAt", Instant.EPOCH);
        }
        Long bot = tx.execute(s -> users.save(User.bot("feed-" + UUID.randomUUID().toString().substring(0, 8), "봇", "")).getId());
        createdUsers.add(bot);
        error(() -> query.list(bot, null, null, 20), "AUTH_INVALID_TOKEN");
        error(() -> commands.like(bot, id, true), "AUTH_INVALID_TOKEN");
        error(() -> imageTransactions.reserve(bot, 10, 10), "AUTH_INVALID_TOKEN");
    }

    @Test void 업로드중_탈퇴해도_완료하지_못하고_만료후_원본을_정리한다() throws Exception {
        Long owner = user();
        doAnswer(i -> {
            objects.put(i.getArgument(0), i.getArgument(1));
            withdrawal.withdraw(owner);
            return null;
        }).when(storage).put(anyString(), any());
        assertThatThrownBy(() -> images.upload(owner, photo())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode().code()).isEqualTo("AUTH_INVALID_TOKEN"));
        Long imageId = jdbc.queryForObject("select id from feed_images where owner_id=?", Long.class, owner);
        cleanup.image(imageId);
        assertThat(imageRows.findById(imageId)).isPresent();
        tx.executeWithoutResult(s -> imageRows.findById(imageId).orElseThrow().expire(Instant.now().minusSeconds(1)));
        cleanup.image(imageId);
        assertThat(imageRows.findById(imageId)).isEmpty();
        assertThat(objects).isEmpty();
    }

    private <T> List<T> concurrent(int count, Callable<T> task) throws Exception {
        try (var pool=Executors.newFixedThreadPool(count)) {
            var start=new CountDownLatch(1); List<Future<T>> futures=new ArrayList<>();
            for(int i=0;i<count;i++) futures.add(pool.submit(() -> { start.await(); return task.call(); }));
            start.countDown(); List<T> result=new ArrayList<>();
            for(var f:futures) result.add(f.get(30,TimeUnit.SECONDS));
            return result;
        }
    }
}
