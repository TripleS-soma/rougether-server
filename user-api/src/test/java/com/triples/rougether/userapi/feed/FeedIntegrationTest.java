package com.triples.rougether.userapi.feed;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.feed.repository.*;
import com.triples.rougether.domain.feed.entity.FeedBoardType;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.feed.dto.*;
import com.triples.rougether.userapi.feed.service.*;
import com.triples.rougether.userapi.global.security.MemberRole;
import com.triples.rougether.userapi.member.service.MemberWithdrawalService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
    @Autowired RoutineRepository routineRows;
    @Autowired RoutineLogRepository routineLogs;
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
                jdbc.update("delete l from routine_logs l join routines r on r.id=l.routine_id where r.user_id=?", id);
                jdbc.update("delete from routines where user_id=?", id);
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
        return verification(owner, text, List.of(images.upload(owner, photo()).imageId()));
    }
    // 게시판 생략(인증게시판) 요청에 오늘 완료한 루틴을 연결함
    FeedCreateRequest verification(Long owner, String text, List<Long> imageIds) {
        return new FeedCreateRequest(UUID.randomUUID(), text, imageIds, null, completion(completedRoutine(owner, "아침 운동", today()), today()));
    }
    static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Seoul")); }
    static FeedRoutineCompletionRequest completion(Routine routine, LocalDate date) {
        return new FeedRoutineCompletionRequest(routine.getId(), date);
    }
    Routine routine(Long owner, String title) {
        return tx.execute(s -> {
            Routine routine = routineRows.save(Routine.create(users.findById(owner).orElseThrow(), null, title,
                    AuthType.CHECK, "DAILY", null, null, null, null));
            routine.assignOriginToSelf();
            return routineRows.save(routine);
        });
    }
    Routine completedRoutine(Long owner, String title, LocalDate date) {
        Routine routine = routine(owner, title);
        complete(routine, date);
        return routine;
    }
    void complete(Routine routine, LocalDate date) {
        tx.executeWithoutResult(s -> routineLogs.save(RoutineLog.complete(routineRows.findById(routine.getId()).orElseThrow(),
                date, Instant.now(), CurrencyType.COIN, 0)));
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
        Routine done = completedRoutine(owner, "물 마시기", today());
        var req = new FeedCreateRequest(UUID.randomUUID(), "일상과 루틴 인증", List.of(imageId), null, completion(done, today()));
        var created = mvc.perform(post("/api/v1/feed/posts").header("Authorization", auth(owner))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.mine").value(true))
                .andExpect(jsonPath("$.boardType").value("VERIFICATION"))
                .andExpect(jsonPath("$.routine.routineId").value(done.getId()))
                .andExpect(jsonPath("$.routine.title").value("물 마시기"))
                .andExpect(jsonPath("$.routine.date").value(today().toString()))
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
        var req = verification(owner, "순서", List.of(b, a));
        Long id = commands.create(owner, req);
        assertThat(commands.create(owner, req)).isEqualTo(id);
        assertThat(query.get(other, id).images()).extracting(FeedImageResponse::imageId).containsExactly(b, a);
        error(() -> commands.create(owner, new FeedCreateRequest(req.clientPostId(), "다른 본문", req.imageIds())), "FEED_REQUEST_CONFLICT");
        Long foreign = images.upload(other, photo()).imageId();
        error(() -> commands.create(owner, verification(owner, "", List.of(foreign))), "FEED_IMAGE_UNAVAILABLE");
        error(() -> commands.create(owner, verification(owner, "", List.of(a))), "FEED_IMAGE_UNAVAILABLE");
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
        Long published=commands.create(owner, verification(owner, "게시됨", List.of(b.imageId())));
        tx.executeWithoutResult(s -> {
            imageRows.findById(a.imageId()).orElseThrow().expire(Instant.now().minusSeconds(1));
            imageRows.findById(b.imageId()).orElseThrow().expire(Instant.now().minusSeconds(1));
        });
        error(() -> commands.create(owner, verification(owner, "만료", List.of(a.imageId()))), "FEED_IMAGE_UNAVAILABLE");
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

    @Test void 자유게시판은_사진없이_작성하고_본문수정과_기존반응을_사용한다() throws Exception {
        Long owner = user(), viewer = user();
        String body = """
                {"clientPostId":"%s","boardType":"FREE","content":"  자유로운 이야기  "}
                """.formatted(UUID.randomUUID());
        var response = mvc.perform(post("/api/v1/feed/posts").header("Authorization", auth(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.boardType").value("FREE"))
                .andExpect(jsonPath("$.content").value("자유로운 이야기"))
                .andExpect(jsonPath("$.images").isEmpty()).andReturn().getResponse();
        long id = mapper.readTree(response.getContentAsString()).path("postId").asLong();
        mvc.perform(post("/api/v1/feed/posts").header("Authorization", auth(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.postId").value(id));
        mvc.perform(patch("/api/v1/feed/posts/" + id).header("Authorization", auth(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\" \"}"))
                .andExpect(status().isBadRequest());
        error(() -> commands.update(viewer, id, "다른 사람 수정"), "FEED_FORBIDDEN");
        commands.update(owner, id, "수정한 자유글");
        commands.like(viewer, id, true);
        commands.comment(viewer, id, new FeedCommentRequest(UUID.randomUUID(), "공감해요"));
        var post = query.get(viewer, id);
        assertThat(post.content()).isEqualTo("수정한 자유글");
        assertThat(post.boardType()).isEqualTo(FeedBoardType.FREE);
        assertThat(post.likeCount()).isEqualTo(1);
        assertThat(post.commentCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notification where user_id=? and ref_id=? and type='FEED_COMMENT'",
                Long.class, owner, id)).isEqualTo(1);
        commands.delete(owner, id);
        error(() -> query.get(viewer, id), "FEED_POST_NOT_FOUND");
    }

    @Test void 게시판별_필수내용과_알수없는_종류를_HTTP에서_검증한다() throws Exception {
        Long owner = user();
        for (String fields : List.of(
                "\"boardType\":\"FREE\",\"content\":\"  \",\"imageIds\":[]",
                "\"boardType\":\"VERIFICATION\",\"content\":\"사진 없음\"",
                "\"content\":\"종류 생략도 사진 필수\",\"imageIds\":[]",
                "\"boardType\":\"UNKNOWN\",\"content\":\"잘못된 종류\"")) {
            mvc.perform(post("/api/v1/feed/posts").header("Authorization", auth(owner))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"clientPostId\":\"" + UUID.randomUUID() + "\"," + fields + "}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/feed/posts").param("boardType", "UNKNOWN").header("Authorization", auth(owner)))
                .andExpect(status().isBadRequest());
    }

    @Test void 게시판필터는_작성자와_커서조건을_함께_적용하고_생략하면_통합조회한다() throws Exception {
        Long owner = user(), viewer = user();
        Long first = commands.create(owner, new FeedCreateRequest(UUID.randomUUID(), "첫 자유글", null, FeedBoardType.FREE));
        Long verification = createPost(owner);
        Long second = commands.create(owner, new FeedCreateRequest(UUID.randomUUID(), "둘째 자유글", List.of(), FeedBoardType.FREE));
        commands.create(viewer, new FeedCreateRequest(UUID.randomUUID(), "다른 작성자", null, FeedBoardType.FREE));
        var page = query.list(viewer, owner, null, 1, FeedBoardType.FREE);
        assertThat(page.items()).extracting(FeedPostResponse::postId).containsExactly(second);
        assertThat(page.hasNext()).isTrue();
        var next = query.list(viewer, owner, page.nextCursor(), 1, FeedBoardType.FREE);
        assertThat(next.items()).extracting(FeedPostResponse::postId).containsExactly(first);
        assertThat(next.hasNext()).isFalse();
        assertThat(next.nextCursor()).isNull();
        assertThat(query.list(viewer, owner, null, 20).items()).extracting(FeedPostResponse::postId)
                .containsExactly(second, verification, first);
        mvc.perform(get("/api/v1/feed/posts").param("boardType", "VERIFICATION")
                        .param("authorId", owner.toString()).header("Authorization", auth(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].postId").value(verification))
                .andExpect(jsonPath("$.items[0].boardType").value("VERIFICATION"));
        commands.delete(owner, second);
        assertThat(query.list(viewer, owner, null, 20, FeedBoardType.FREE).items())
                .extracting(FeedPostResponse::postId).containsExactly(first);
    }

    @Test void 구버전_재시도_hash를_유지하며_같은_UUID로_게시판을_바꿀수없다() throws Exception {
        Long owner = user(); var legacy = request(owner, "기존 사진글");
        Long id = commands.create(owner, legacy);
        var routineLink = legacy.routineCompletion();
        // 루틴 연결이 없는 글은 기존 hash 형식, 있는 글은 루틴 식별자를 덧붙인 형식
        String linkedHash = sha256(legacy.content() + "\u0000" + legacy.imageIds()
                + "\u0000routine:" + routineLink.routineId() + ":" + routineLink.date());
        assertThat(posts.findById(id).orElseThrow().getRequestHash()).isEqualTo(linkedHash);
        var photoFree = new FeedCreateRequest(UUID.randomUUID(), "사진 자유글", List.of(images.upload(owner, photo()).imageId()), FeedBoardType.FREE);
        Long photoFreeId = commands.create(owner, photoFree);
        assertThat(posts.findById(photoFreeId).orElseThrow().getRequestHash())
                .isEqualTo(sha256(photoFree.content() + "\u0000" + photoFree.imageIds()));
        commands.update(owner, id, "수정된 내용");
        assertThat(commands.create(owner, new FeedCreateRequest(legacy.clientPostId(), legacy.content(),
                legacy.imageIds(), FeedBoardType.VERIFICATION, routineLink))).isEqualTo(id);
        assertThat(query.get(owner, id).content()).isEqualTo("수정된 내용");
        error(() -> commands.create(owner, new FeedCreateRequest(legacy.clientPostId(), legacy.content(),
                legacy.imageIds(), FeedBoardType.FREE)), "FEED_REQUEST_CONFLICT");
        // 같은 UUID로 다른 루틴을 연결하면 다른 요청으로 봄
        Routine other = completedRoutine(owner, "다른 루틴", today());
        error(() -> commands.create(owner, new FeedCreateRequest(legacy.clientPostId(), legacy.content(),
                legacy.imageIds(), null, completion(other, today()))), "FEED_REQUEST_CONFLICT");
        var free = new FeedCreateRequest(UUID.randomUUID(), "자유글", null, FeedBoardType.FREE);
        Long freeId = commands.create(owner, free);
        assertThat(commands.create(owner, new FeedCreateRequest(free.clientPostId(), free.content(), List.of(), FeedBoardType.FREE)))
                .isEqualTo(freeId);
        commands.delete(owner, freeId);
        error(() -> commands.create(owner, free), "FEED_REQUEST_CONFLICT");
    }

    @Test void 사진이_있는_자유글은_빈본문을_허용하되_이미지소유권을_검사한다() throws Exception {
        Long owner = user(), other = user(); Long imageId = images.upload(owner, photo()).imageId();
        error(() -> commands.create(other, new FeedCreateRequest(UUID.randomUUID(), "", List.of(imageId), FeedBoardType.FREE)),
                "FEED_IMAGE_UNAVAILABLE");
        Long id = commands.create(owner, new FeedCreateRequest(UUID.randomUUID(), "", List.of(imageId), FeedBoardType.FREE));
        commands.update(owner, id, " ");
        assertThat(query.get(other, id).content()).isEmpty();
        assertThat(query.get(other, id).images()).hasSize(1);
    }

    @Test void 인증글은_본인의_완료기록만_연결하고_자유글에는_연결할수없다() throws Exception {
        Long owner = user(), other = user();
        LocalDate today = today();
        Routine done = completedRoutine(owner, "독서", today);
        Long photo = images.upload(owner, photo()).imageId();
        error(() -> commands.create(owner, new FeedCreateRequest(UUID.randomUUID(), "", List.of(photo))),
                "FEED_ROUTINE_COMPLETION_REQUIRED");
        error(() -> commands.create(owner, new FeedCreateRequest(UUID.randomUUID(), "자유글", List.of(), FeedBoardType.FREE,
                completion(done, today))), "FEED_INPUT_INVALID");
        // 남의 루틴·미완료 날짜·미래 날짜·기간(오늘~6일 전) 밖·없는 루틴은 같은 코드로 거절함
        Routine foreign = completedRoutine(other, "남의 루틴", today);
        Routine notDone = routine(owner, "아직 안 함");
        Routine old = completedRoutine(owner, "지난주", today.minusDays(7));
        complete(done, today.minusDays(6));
        for (FeedRoutineCompletionRequest invalid : List.of(completion(foreign, today), completion(notDone, today),
                completion(done, today.plusDays(1)), completion(done, today.minusDays(1)), completion(old, today.minusDays(7)),
                new FeedRoutineCompletionRequest(Long.MAX_VALUE, today))) {
            error(() -> commands.create(owner, new FeedCreateRequest(UUID.randomUUID(), "", List.of(photo), null, invalid)),
                    "FEED_ROUTINE_COMPLETION_INVALID");
        }
        Long weekAgo = commands.create(owner, new FeedCreateRequest(UUID.randomUUID(), "", List.of(photo),
                FeedBoardType.VERIFICATION, completion(done, today.minusDays(6))));
        assertThat(query.get(other, weekAgo).routine()).isEqualTo(new FeedRoutineResponse(done.getId(), "독서", today.minusDays(6)));
        // 연결 후 루틴 이름을 바꿔도 연결 시점 제목을 유지함
        tx.executeWithoutResult(s -> routineRows.findById(done.getId()).orElseThrow()
                .update("새 이름", null, null, null, null, null, null));
        assertThat(query.get(other, weekAgo).routine().title()).isEqualTo("독서");
        mvc.perform(post("/api/v1/feed/posts").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientPostId\":\"" + UUID.randomUUID() + "\",\"imageIds\":[" + images.upload(owner, photo()).imageId()
                                + "],\"routineCompletion\":{\"routineId\":" + done.getId() + ",\"date\":\"" + today.plusDays(1) + "\"}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FEED_ROUTINE_COMPLETION_INVALID"));
        mvc.perform(get("/api/v1/feed/posts").param("boardType", "FREE").header("Authorization", auth(owner)))
                .andExpect(status().isOk());
    }

    @Test void 수정으로_갈린_옛버전의_완료기록도_계보로_인정하고_현재_제목을_보인다() throws Exception {
        Long owner = user();
        LocalDate today = today();
        Routine v1 = completedRoutine(owner, "옛 이름", today);
        Routine v2 = tx.execute(s -> {
            Routine origin = routineRows.findById(v1.getId()).orElseThrow();
            Routine copy = routineRows.save(origin.copyAsNewVersion(null, "새 이름", null, null, null, null, null, null));
            origin.softDelete(Instant.now());
            return copy;
        });
        Long photo = images.upload(owner, photo()).imageId();
        Long viaNew = commands.create(owner, new FeedCreateRequest(UUID.randomUUID(), "", List.of(photo), null, completion(v2, today)));
        assertThat(query.get(owner, viaNew).routine()).isEqualTo(new FeedRoutineResponse(v2.getId(), "새 이름", today));
        Long viaOld = commands.create(owner, verification(owner, "", List.of(images.upload(owner, photo()).imageId())));
        commands.update(owner, viaOld, new FeedUpdateRequest(null, null, completion(v1, today)));
        assertThat(query.get(owner, viaOld).routine()).isEqualTo(new FeedRoutineResponse(v1.getId(), "새 이름", today));
    }

    @Test void 수정으로_게시판을_바꾸고_루틴연결을_교체하거나_해제한다() throws Exception {
        Long owner = user(), viewer = user();
        LocalDate today = today();
        Routine first = completedRoutine(owner, "산책", today), second = completedRoutine(owner, "명상", today);
        Long textOnly = commands.create(owner, new FeedCreateRequest(UUID.randomUUID(), "글만", List.of(), FeedBoardType.FREE));
        // 사진은 수정할 수 없으므로 사진 없는 글은 인증게시판으로 옮길 수 없음
        error(() -> commands.update(owner, textOnly, new FeedUpdateRequest(null, FeedBoardType.VERIFICATION, completion(first, today))),
                "FEED_INPUT_INVALID");
        error(() -> commands.update(owner, textOnly, new FeedUpdateRequest(null, null, completion(first, today))), "FEED_INPUT_INVALID");
        error(() -> commands.update(owner, textOnly, new FeedUpdateRequest(null, null, null)), "FEED_INPUT_INVALID");
        Long photoFree = commands.create(owner, new FeedCreateRequest(UUID.randomUUID(), "", List.of(images.upload(owner, photo()).imageId()), FeedBoardType.FREE));
        error(() -> commands.update(owner, photoFree, new FeedUpdateRequest(null, FeedBoardType.VERIFICATION, null)),
                "FEED_ROUTINE_COMPLETION_REQUIRED");
        error(() -> commands.update(owner, photoFree, new FeedUpdateRequest(null, FeedBoardType.VERIFICATION,
                completion(first, today.plusDays(1)))), "FEED_ROUTINE_COMPLETION_INVALID");
        error(() -> commands.update(viewer, photoFree, new FeedUpdateRequest(null, FeedBoardType.VERIFICATION, completion(first, today))),
                "FEED_FORBIDDEN");
        assertThat(query.get(owner, photoFree).boardType()).isEqualTo(FeedBoardType.FREE);
        String path = "/api/v1/feed/posts/" + photoFree;
        mvc.perform(patch(path).header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardType\":\"VERIFICATION\",\"routineCompletion\":{\"routineId\":" + first.getId()
                                + ",\"date\":\"" + today + "\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.boardType").value("VERIFICATION"))
                .andExpect(jsonPath("$.routine.title").value("산책")).andExpect(jsonPath("$.content").value(""));
        // 구버전 { content } 수정은 게시판과 루틴 연결을 그대로 둠
        mvc.perform(patch(path).header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"본문만 수정\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.routine.title").value("산책"))
                .andExpect(jsonPath("$.boardType").value("VERIFICATION"));
        commands.update(owner, photoFree, new FeedUpdateRequest(null, FeedBoardType.VERIFICATION, completion(second, today)));
        assertThat(query.get(viewer, photoFree).routine().title()).isEqualTo("명상");
        assertThat(query.list(viewer, owner, null, 20, FeedBoardType.VERIFICATION).items())
                .extracting(FeedPostResponse::postId).containsExactly(photoFree);
        mvc.perform(patch(path).header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"boardType\":\"FREE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.boardType").value("FREE"))
                .andExpect(jsonPath("$.routine").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.content").value("본문만 수정"));
        assertThat(query.list(viewer, owner, null, 20, FeedBoardType.VERIFICATION).items()).isEmpty();
        // 사진 없는 자유글의 본문 규칙은 그대로
        error(() -> commands.update(owner, textOnly, new FeedUpdateRequest(" ", FeedBoardType.FREE, null)), "FEED_INPUT_INVALID");
    }

    @Test void 연결없는_기존_인증글은_본문수정만으로_유효하다() throws Exception {
        Long owner = user(); Long id = createPost(owner);
        jdbc.update("update feed_posts set routine_id=null, routine_date=null, routine_title=null where id=?", id);
        commands.update(owner, id, "기존 글 수정");
        var post = query.get(owner, id);
        assertThat(post.boardType()).isEqualTo(FeedBoardType.VERIFICATION);
        assertThat(post.routine()).isNull();
        assertThat(post.content()).isEqualTo("기존 글 수정");
        commands.delete(owner, id);
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
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
