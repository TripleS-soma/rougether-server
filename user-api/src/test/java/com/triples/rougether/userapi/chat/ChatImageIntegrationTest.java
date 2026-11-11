package com.triples.rougether.userapi.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.chat.entity.ChatImage;
import com.triples.rougether.domain.chat.entity.ChatMessageType;
import com.triples.rougether.domain.chat.repository.ChatImageRepository;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.aiuser.service.AiUserEvents;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.chat.dto.ChatMessageImageResponse;
import com.triples.rougether.userapi.chat.dto.ChatMessageResponse;
import com.triples.rougether.userapi.chat.dto.ChatSendRequest;
import com.triples.rougether.userapi.chat.realtime.ChatSocketHandler;
import com.triples.rougether.userapi.chat.service.ChatCleanupJob;
import com.triples.rougether.userapi.chat.service.ChatCleanupTransactions;
import com.triples.rougether.userapi.chat.service.ChatCommandService;
import com.triples.rougether.userapi.chat.service.ChatImageService;
import com.triples.rougether.userapi.chat.service.ChatImageStorage;
import com.triples.rougether.userapi.global.image.PrivatePhotoNormalizer;
import com.triples.rougether.userapi.chat.service.ChatQueryService;
import com.triples.rougether.userapi.global.security.MemberRole;
import com.triples.rougether.userapi.house.service.HouseMemberCommandService;
import com.triples.rougether.userapi.logingift.service.LoginGiftAccessRecorder;
import com.triples.rougether.userapi.notification.fcm.FcmPushExecutor;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector="
        + "com.triples.rougether.userapi.chat.ChatImageIntegrationTest$QueryCounter")
@AutoConfigureMockMvc
class ChatImageIntegrationTest {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired ChatCommandService commands;
    @MockitoSpyBean ChatQueryService query;
    @MockitoSpyBean AiUserEvents aiEvents;
    @MockitoBean FcmPushExecutor fcm;
    @Autowired ChatImageService images;
    @Autowired ChatCleanupTransactions cleanup;
    @Autowired ChatCleanupJob job;
    @Autowired ChatImageRepository imageRows;
    @Autowired UserRepository users;
    @Autowired HouseRepository houses;
    @Autowired HouseMemberRepository members;
    @Autowired HouseMemberCommandService membershipCommands;
    @Autowired TransactionTemplate tx;
    @Autowired TokenService tokens;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ChatImageStorage storage;
    // 기간 한정 접속 선물이 끼면 실행 날짜에 따라 사용자 정리가 깨지므로 이 테스트에서는 끔.
    @MockitoBean LoginGiftAccessRecorder loginGiftAccess;

    private final List<Fixture> fixtures = new ArrayList<>();
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    record Fixture(Long owner, Long member, Long outsider, Long house, Long room) { }

    @BeforeEach
    void fakeStorage() {
        doAnswer(i -> { objects.put(i.getArgument(0), i.getArgument(1)); return null; })
                .when(storage).put(anyString(), any());
        when(storage.read(anyString())).thenAnswer(i -> objects.get(i.getArgument(0)));
        doAnswer(i -> { objects.remove(i.getArgument(0)); return null; }).when(storage).delete(anyString());
    }

    @AfterEach
    void clean() {
        tx.executeWithoutResult(status -> {
            Set<Long> userIds = new HashSet<>();
            for (Fixture f : fixtures) {
                userIds.addAll(jdbc.queryForList("select user_id from house_members where house_id = ?", Long.class, f.house()));
                userIds.add(f.outsider());
                jdbc.update("delete from chat_images where room_id = ?", f.room());
                jdbc.update("delete from chat_messages where room_id = ?", f.room());
                jdbc.update("delete from chat_read_states where room_id = ?", f.room());
                jdbc.update("delete from chat_rooms where id = ?", f.room());
                jdbc.update("delete from house_members where house_id = ?", f.house());
                jdbc.update("delete from house where id = ?", f.house());
            }
            for (Long userId : userIds) {
                jdbc.update("delete from notification where user_id = ?", userId);
                jdbc.update("delete from user_daily_activity where user_id = ?", userId);
                jdbc.update("delete from users where id = ?", userId);
            }
        });
    }

    Fixture fixture() {
        Fixture fixture = tx.execute(status -> {
            String key = UUID.randomUUID().toString();
            User owner = users.save(User.signUp("chat-image-owner-" + key + "@test.dev"));
            User member = users.save(User.signUp("chat-image-member-" + key + "@test.dev"));
            User outsider = users.save(User.signUp("chat-image-other-" + key + "@test.dev"));
            House house = houses.save(House.create(owner, "사진 테스트", null, null, 4,
                    key.substring(0, 8), Instant.now().plusSeconds(3600)));
            members.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
            members.save(HouseMember.create(house, member, HouseMemberRole.MEMBER));
            house.increaseMemberCount();
            Long room = commands.openHouse(owner.getId(), house.getId()).roomId();
            return new Fixture(owner.getId(), member.getId(), outsider.getId(), house.getId(), room);
        });
        fixtures.add(fixture);
        return fixture;
    }

    String auth(Long id) { return "Bearer " + tokens.issueAccessToken(id, MemberRole.NORMAL); }

    static byte[] encoded(int width, int height, String format) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }

    static MockMultipartFile photo() throws Exception {
        return new MockMultipartFile("file", "photo.png", "image/png", encoded(48, 32, "png"));
    }

    ResultActions upload(Long room, Long user, MockMultipartFile file) throws Exception {
        return mvc.perform(multipart("/api/v1/chat/rooms/" + room + "/images").file(file)
                .header("Authorization", auth(user)));
    }

    Long uploaded(Fixture f, Long user) throws Exception {
        return images.upload(user, f.room(), photo()).imageId();
    }

    ChatMessageResponse sendPhoto(Fixture f, Long user, Long imageId) {
        return commands.send(user, f.room(), new ChatSendRequest(UUID.randomUUID(), null, imageId));
    }

    void markSent(Fixture f, Long imageId) {
        sendPhoto(f, f.owner(), imageId);
    }

    long messageCount(Fixture f) {
        return jdbc.queryForObject("select count(*) from chat_messages where room_id = ?", Long.class, f.room());
    }

    void joinHouse(Long userId, Long houseId) {
        tx.executeWithoutResult(s -> members.save(HouseMember.create(houses.getReferenceById(houseId),
                users.getReferenceById(userId), HouseMemberRole.MEMBER)));
    }

    // 전역 Hibernate 통계에는 비동기 푸시의 SQL도 섞이므로 조회 스레드만 센다.
    public static class QueryCounter implements StatementInspector {
        private static final ThreadLocal<Long> COUNT = new ThreadLocal<>();

        @Override
        public String inspect(String sql) {
            Long count = COUNT.get();
            if (count != null) COUNT.set(count + 1);
            return sql;
        }
    }

    long statementsDuring(Runnable action) {
        QueryCounter.COUNT.set(0L);
        try {
            action.run();
            return QueryCounter.COUNT.get();
        } finally {
            QueryCounter.COUNT.remove();
        }
    }

    // 지난날 올려서 이미 만료된 상태로 만듦. 오늘 올린 행은 하루 한도 집계 때문에 정리되지 않음.
    void expire(Long imageId) {
        jdbc.update("update chat_images set created_at = date_sub(utc_timestamp(6), interval 2 day), "
                + "expires_at = date_sub(utc_timestamp(6), interval 1 second) where id = ?", imageId);
    }

    void seed(Fixture f, Long ownerId, int count, Instant createdAt, Instant expiresAt) {
        tx.executeWithoutResult(s -> {
            User owner = users.getReferenceById(ownerId);
            for (int i = 0; i < count; i++) {
                imageRows.save(ChatImage.reserve(f.room(), owner, "private/chat/" + UUID.randomUUID() + ".jpg",
                        1, 1, createdAt, expiresAt));
            }
        });
    }

    void error(Runnable runnable, String code) {
        assertThatThrownBy(runnable::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode().code()).isEqualTo(code));
    }

    List<String> concurrently(int count, Callable<String> action) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(count)) {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return action.call();
                    } catch (BusinessException e) {
                        return e.getErrorCode().code();
                    }
                }));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        }
    }

    List<String> uploadConcurrently(Fixture f, Long user, int count) throws Exception {
        return concurrently(count, () -> {
            images.upload(user, f.room(), photo());
            return "OK";
        });
    }

    long unsentCount(Long owner) {
        return imageRows.countByOwnerIdAndMessageIdIsNullAndExpiresAtAfter(owner, Instant.now());
    }

    @Test
    void HTTP로_올린_사진은_저장키_없이_응답하고_보안헤더로_조회되며_취소할_수_있다() throws Exception {
        Fixture f = fixture();
        String body = upload(f.room(), f.owner(), photo())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.width").value(48))
                .andExpect(jsonPath("$.height").value(32))
                .andExpect(jsonPath("$.contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long imageId = mapper.readTree(body).path("imageId").asLong();

        mvc.perform(get("/api/v1/chat/images/" + imageId).header("Authorization", auth(f.owner())))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string("Cache-Control", allOf(containsString("no-store"), containsString("private"))))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        mvc.perform(delete("/api/v1/chat/images/" + imageId).header("Authorization", auth(f.owner())))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/chat/images/" + imageId).header("Authorization", auth(f.owner())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAT_IMAGE_NOT_FOUND"));
    }

    @Test
    void 잘못된_사진과_권한_없는_업로드는_저장소에_닿지_않는다() throws Exception {
        Fixture f = fixture();
        upload(f.room(), f.owner(), new MockMultipartFile("file", "forged.jpg", "image/jpeg",
                "not a photo".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CHAT_IMAGE_INVALID"));
        upload(f.room(), f.owner(), new MockMultipartFile("file", "a.gif", "image/gif", encoded(16, 16, "gif")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CHAT_IMAGE_INVALID"));
        upload(f.room(), f.outsider(), photo())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CHAT_FORBIDDEN"));
        upload(Long.MAX_VALUE, f.owner(), photo())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CHAT_ROOM_NOT_FOUND"));
        mvc.perform(multipart("/api/v1/chat/rooms/" + f.room() + "/images").file(photo()))
                .andExpect(status().isUnauthorized());
        verify(storage, never()).put(anyString(), any());
        assertThat(jdbc.queryForObject("select count(*) from chat_images where room_id = ?", Long.class, f.room())).isZero();
    }

    @Test
    void 집을_나간_구성원_탈퇴자_봇은_사진을_올릴_수_없다() throws Exception {
        Fixture left = fixture();
        membershipCommands.leave(left.member(), left.house());
        error(() -> upload(left, left.member()), "CHAT_FORBIDDEN");

        Fixture withdrawn = fixture();
        jdbc.update("update users set deleted_at = ? where id = ?", Timestamp.from(Instant.now()), withdrawn.member());
        error(() -> upload(withdrawn, withdrawn.member()), "CHAT_FORBIDDEN");

        Fixture bot = fixture();
        jdbc.update("update users set is_bot = true where id = ?", bot.member());
        error(() -> upload(bot, bot.member()), "CHAT_FORBIDDEN");
        verify(storage, never()).put(anyString(), any());
    }

    void upload(Fixture f, Long user) {
        try {
            images.upload(user, f.room(), photo());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 인증_없이는_사진을_조회하거나_취소할_수_없다() throws Exception {
        mvc.perform(get("/api/v1/chat/images/1")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/chat/images/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void 한도_초과는_HTTP_429이고_쓸_수_없는_사진_전송은_HTTP_409다() throws Exception {
        Fixture f = fixture();
        Instant now = Instant.now();
        Long used = uploaded(f, f.owner());
        sendPhoto(f, f.owner(), used);
        mvc.perform(post("/api/v1/chat/rooms/" + f.room() + "/messages").header("Authorization", auth(f.owner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ChatSendRequest(UUID.randomUUID(), null, used))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHAT_IMAGE_UNAVAILABLE"));

        seed(f, f.owner(), 30, now, now.plusSeconds(3600));
        upload(f.room(), f.owner(), photo())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("CHAT_IMAGE_LIMIT"));
    }

    @Test
    void 취소한_사진도_오늘_한도에_남고_다음날_정리된다() throws Exception {
        Fixture f = fixture();
        Instant now = Instant.now();
        seed(f, f.owner(), 49, now, now.minusSeconds(1));
        Long cancelled = uploaded(f, f.owner());
        images.cancel(f.owner(), cancelled);

        job.run();

        assertThat(imageRows.findById(cancelled)).isPresent();
        error(() -> upload(f, f.owner()), "CHAT_IMAGE_LIMIT");

        expire(cancelled);
        job.run();
        assertThat(imageRows.findById(cancelled)).isEmpty();
    }

    @Test
    void 정리_작업은_한_페이지를_넘는_대상도_모두_지운다() {
        Fixture f = fixture();
        Instant yesterday = LocalDate.now(KST).minusDays(1).atTime(12, 0).atZone(KST).toInstant();
        seed(f, f.owner(), 105, yesterday, yesterday.plusSeconds(1));

        job.run();

        assertThat(jdbc.queryForObject("select count(*) from chat_images where room_id = ?", Long.class, f.room())).isZero();
    }

    @Test
    void 저장본은_EXIF가_제거된_JPEG이고_채팅_전용_key를_쓴다() throws Exception {
        Fixture f = fixture();
        byte[] jpeg = encoded(64, 40, "jpeg");
        byte[] exif = HexFormat.of().parseHex(
                "ffe1002245786966000049492a0008000000010012010300010000000600000000000000");
        var original = new ByteArrayOutputStream();
        original.write(jpeg, 0, 2);
        original.write(exif);
        original.write(jpeg, 2, jpeg.length - 2);

        var response = images.upload(f.owner(), f.room(), new MockMultipartFile("file", original.toByteArray()));

        assertThat(response.width()).isEqualTo(40);
        assertThat(response.height()).isEqualTo(64);
        ChatImage row = imageRows.findById(response.imageId()).orElseThrow();
        assertThat(row.getStorageKey()).matches("private/chat/[a-f0-9-]{36}\\.jpg");
        assertThat(row.isReady()).isTrue();
        assertThat(row.getRoomId()).isEqualTo(f.room());
        assertThat(row.getMessageId()).isNull();
        byte[] stored = objects.get(row.getStorageKey());
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
        assertThat(stored[0] & 255).isEqualTo(255);
        assertThat(stored[1] & 255).isEqualTo(216);
    }

    @Test
    void 전송_전_사진은_올린_사람만_볼_수_있다() throws Exception {
        Fixture f = fixture();
        Long imageId = uploaded(f, f.member());
        assertThat(images.read(f.member(), imageId)).isNotEmpty();
        error(() -> images.read(f.owner(), imageId), "CHAT_IMAGE_NOT_FOUND");
        error(() -> images.read(f.outsider(), imageId), "CHAT_IMAGE_NOT_FOUND");
    }

    @Test
    void 전송된_사진은_현재_구성원만_보고_나간_구성원은_볼_수_없다() throws Exception {
        Fixture f = fixture();
        Long imageId = uploaded(f, f.owner());
        markSent(f, imageId);

        assertThat(images.read(f.owner(), imageId)).isNotEmpty();
        assertThat(images.read(f.member(), imageId)).isNotEmpty();
        error(() -> images.read(f.outsider(), imageId), "CHAT_IMAGE_NOT_FOUND");

        membershipCommands.leave(f.member(), f.house());
        error(() -> images.read(f.member(), imageId), "CHAT_IMAGE_NOT_FOUND");
        assertThat(images.read(f.owner(), imageId)).isNotEmpty();
    }

    @Test
    void 만료된_미전송_사진은_올린_사람도_볼_수_없다() throws Exception {
        Fixture f = fixture();
        Long imageId = uploaded(f, f.owner());
        expire(imageId);
        error(() -> images.read(f.owner(), imageId), "CHAT_IMAGE_NOT_FOUND");
    }

    @Test
    void 저장소에_파일이_없으면_404이고_읽기_실패는_503이다() throws Exception {
        Fixture f = fixture();
        Long imageId = uploaded(f, f.owner());
        String key = imageRows.findById(imageId).orElseThrow().getStorageKey();
        when(storage.read(key)).thenThrow(NoSuchKeyException.builder().build());
        error(() -> images.read(f.owner(), imageId), "CHAT_IMAGE_NOT_FOUND");
        doThrow(new RuntimeException("s3 down")).when(storage).read(key);
        error(() -> images.read(f.owner(), imageId), "CHAT_STORAGE_UNAVAILABLE");
    }

    @Test
    void 취소는_본인의_미전송_사진만_가능하다() throws Exception {
        Fixture f = fixture();
        Long imageId = uploaded(f, f.owner());
        error(() -> images.cancel(f.member(), imageId), "CHAT_IMAGE_NOT_FOUND");
        markSent(f, imageId);
        error(() -> images.cancel(f.owner(), imageId), "CHAT_IMAGE_UNAVAILABLE");
        error(() -> images.cancel(f.owner(), Long.MAX_VALUE), "CHAT_IMAGE_NOT_FOUND");
    }

    @Test
    void 동시에_올려도_미전송_사진은_30장을_넘지_않는다() throws Exception {
        Fixture f = fixture();
        Instant now = Instant.now();
        seed(f, f.owner(), 28, now, now.plusSeconds(3600));

        List<String> results = uploadConcurrently(f, f.owner(), 8);

        assertThat(results).filteredOn("OK"::equals).hasSize(2);
        assertThat(results).filteredOn("CHAT_IMAGE_LIMIT"::equals).hasSize(6);
        assertThat(unsentCount(f.owner())).isEqualTo(30);
    }

    @Test
    void 동시에_올려도_하루_50장을_넘지_않는다() throws Exception {
        Fixture f = fixture();
        Instant now = Instant.now();
        seed(f, f.owner(), 48, now, now.minusSeconds(1));

        List<String> results = uploadConcurrently(f, f.owner(), 8);

        assertThat(results).filteredOn("OK"::equals).hasSize(2);
        assertThat(results).filteredOn("CHAT_IMAGE_LIMIT"::equals).hasSize(6);
        assertThat(jdbc.queryForObject("select count(*) from chat_images where owner_id = ?", Long.class, f.owner()))
                .isEqualTo(50);
    }

    @Test
    void 어제_올린_사진은_오늘_한도에_세지_않는다() throws Exception {
        Fixture f = fixture();
        Instant yesterday = LocalDate.now(KST).minusDays(1).atTime(12, 0).atZone(KST).toInstant();
        seed(f, f.owner(), 50, yesterday, yesterday.plusSeconds(1));

        assertThat(uploaded(f, f.owner())).isNotNull();
    }

    @Test
    void 저장_실패는_503이고_행이_남아_만료_후_정리된다() throws Exception {
        Fixture f = fixture();
        doThrow(new RuntimeException("s3 down")).when(storage).put(anyString(), any());

        upload(f.room(), f.owner(), photo())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CHAT_STORAGE_UNAVAILABLE"));

        Long imageId = jdbc.queryForObject("select id from chat_images where room_id = ?", Long.class, f.room());
        ChatImage row = imageRows.findById(imageId).orElseThrow();
        assertThat(row.isReady()).isFalse();
        assertThat(unsentCount(f.owner())).isZero();
        error(() -> images.read(f.owner(), imageId), "CHAT_IMAGE_NOT_FOUND");

        expire(imageId);
        cleanup.image(imageId);

        verify(storage).delete(row.getStorageKey());
        assertThat(imageRows.findById(imageId)).isEmpty();
    }

    @Test
    void 정리는_S3_실패_시_행을_남겨_재시도하고_유효한_사진과_전송된_사진은_지우지_않는다() throws Exception {
        Fixture f = fixture();
        Long failing = uploaded(f, f.owner());
        Long valid = uploaded(f, f.owner());
        Long sent = uploaded(f, f.owner());
        markSent(f, sent);
        expire(failing);
        expire(sent);
        String failingKey = imageRows.findById(failing).orElseThrow().getStorageKey();
        doThrow(new RuntimeException("temporary")).doNothing().when(storage).delete(failingKey);

        assertThatThrownBy(() -> cleanup.image(failing)).isInstanceOf(RuntimeException.class);
        assertThat(imageRows.findById(failing)).isPresent();

        cleanup.image(failing);
        cleanup.image(valid);
        cleanup.image(sent);

        assertThat(imageRows.findById(failing)).isEmpty();
        assertThat(imageRows.findById(valid)).isPresent();
        assertThat(imageRows.findById(sent)).isPresent();
    }

    @Test
    void 정리_작업은_한_건이_실패해도_나머지를_계속_지운다() throws Exception {
        Fixture f = fixture();
        Long first = uploaded(f, f.owner());
        Long second = uploaded(f, f.owner());
        expire(first);
        expire(second);
        String firstKey = imageRows.findById(first).orElseThrow().getStorageKey();
        doThrow(new RuntimeException("temporary")).when(storage).delete(firstKey);

        job.run();

        assertThat(imageRows.findById(first)).isPresent();
        assertThat(imageRows.findById(second)).isEmpty();
        doNothing().when(storage).delete(firstKey);
    }

    @Test
    void 사진을_보내면_유형과_사진정보가_응답과_기록과_소켓본문에_담기고_글_메시지는_그대로다() throws Exception {
        Fixture f = fixture();
        Long imageId = uploaded(f, f.owner());

        var sent = sendPhoto(f, f.owner(), imageId);
        var text = commands.send(f.member(), f.room(), new ChatSendRequest(UUID.randomUUID(), "좋다"));

        var expected = new ChatMessageImageResponse(imageId, 48, 32);
        assertThat(sent.messageType()).isEqualTo(ChatMessageType.IMAGE);
        assertThat(sent.content()).isEqualTo("사진을 보냈어요");
        assertThat(sent.image()).isEqualTo(expected);
        assertThat(text.messageType()).isEqualTo(ChatMessageType.TEXT);
        assertThat(text.image()).isNull();
        assertThat(imageRows.findById(imageId).orElseThrow().getMessageId()).isEqualTo(sent.messageId());

        var items = query.messages(f.member(), f.room(), null, null, 50).items();
        assertThat(items.getFirst().image()).isNull();
        assertThat(items.getLast().image()).isEqualTo(expected);
        var socket = mapper.readTree(mapper.writeValueAsString(new ChatSocketHandler.MessageCreated("MESSAGE_CREATED", items.getLast())));
        assertThat(socket.at("/message/messageType").asString()).isEqualTo("IMAGE");
        assertThat(socket.at("/message/image/imageId").asLong()).isEqualTo(imageId);
        assertThat(socket.at("/message/content").asString()).isEqualTo("사진을 보냈어요");
    }

    @Test
    void HTTP로_사진을_보내고_내용과_사진은_정확히_하나만_허용한다() throws Exception {
        Fixture f = fixture();
        Long imageId = uploaded(f, f.owner());
        String path = "/api/v1/chat/rooms/" + f.room() + "/messages";
        for (String body : List.of(
                mapper.writeValueAsString(new ChatSendRequest(UUID.randomUUID(), "글", imageId)),
                "{\"clientMessageId\":\"" + UUID.randomUUID() + "\"}",
                mapper.writeValueAsString(new ChatSendRequest(UUID.randomUUID(), " ")))) {
            mvc.perform(post(path).header("Authorization", auth(f.owner()))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CHAT_INPUT_INVALID"));
        }
        assertThat(messageCount(f)).isZero();

        mvc.perform(post(path).header("Authorization", auth(f.owner())).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ChatSendRequest(UUID.randomUUID(), null, imageId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageType").value("IMAGE"))
                .andExpect(jsonPath("$.image.imageId").value(imageId))
                .andExpect(jsonPath("$.content").value("사진을 보냈어요"));
    }

    @Test
    void 남의_사진_다른_방_사진_만료된_사진_미완료_사진_재사용_사진은_보낼_수_없다() throws Exception {
        Fixture f = fixture();
        Fixture other = fixture();
        joinHouse(f.owner(), other.house());
        Long someoneElses = uploaded(f, f.member());
        Long otherRoom = uploaded(other, f.owner());
        Long expired = uploaded(f, f.owner());
        expire(expired);
        Instant now = Instant.now();
        seed(f, f.owner(), 1, now, now.plusSeconds(3600));
        Long notReady = jdbc.queryForObject("select id from chat_images where room_id = ? and ready = false", Long.class, f.room());
        Long used = uploaded(f, f.owner());
        sendPhoto(f, f.owner(), used);

        for (Long imageId : List.of(someoneElses, otherRoom, expired, notReady, used, Long.MAX_VALUE)) {
            error(() -> sendPhoto(f, f.owner(), imageId), "CHAT_IMAGE_UNAVAILABLE");
        }
        assertThat(messageCount(f)).isEqualTo(1);
        assertThat(imageRows.findById(someoneElses).orElseThrow().getMessageId()).isNull();
    }

    @Test
    void 같은_요청_ID로_다시_보내면_같은_메시지이고_내용이_다르면_충돌한다() throws Exception {
        Fixture f = fixture();
        Long first = uploaded(f, f.owner());
        Long second = uploaded(f, f.owner());
        UUID requestId = UUID.randomUUID();

        var sent = commands.send(f.owner(), f.room(), new ChatSendRequest(requestId, null, first));
        var retried = commands.send(f.owner(), f.room(), new ChatSendRequest(requestId, null, first));

        assertThat(retried.messageId()).isEqualTo(sent.messageId());
        assertThat(retried.image()).isEqualTo(sent.image());
        assertThat(messageCount(f)).isEqualTo(1);
        error(() -> commands.send(f.owner(), f.room(), new ChatSendRequest(requestId, null, second)), "CHAT_MESSAGE_CONFLICT");
        error(() -> commands.send(f.owner(), f.room(), new ChatSendRequest(requestId, "글")), "CHAT_MESSAGE_CONFLICT");
        UUID textId = UUID.randomUUID();
        commands.send(f.owner(), f.room(), new ChatSendRequest(textId, "글"));
        error(() -> commands.send(f.owner(), f.room(), new ChatSendRequest(textId, null, second)), "CHAT_MESSAGE_CONFLICT");
        assertThat(imageRows.findById(second).orElseThrow().getMessageId()).isNull();
    }

    @Test
    void 같은_사진을_동시에_보내도_메시지는_하나만_생긴다() throws Exception {
        Fixture f = fixture();
        Long imageId = uploaded(f, f.owner());

        List<String> results = concurrently(6, () -> {
            sendPhoto(f, f.owner(), imageId);
            return "OK";
        });

        assertThat(results).filteredOn("OK"::equals).hasSize(1);
        assertThat(results).filteredOn("CHAT_IMAGE_UNAVAILABLE"::equals).hasSize(5);
        assertThat(messageCount(f)).isEqualTo(1);
    }

    @Test
    void 같은_요청_ID를_동시에_재전송해도_한_번만_저장한다() throws Exception {
        Fixture f = fixture();
        Long imageId = uploaded(f, f.owner());
        var request = new ChatSendRequest(UUID.randomUUID(), null, imageId);

        List<String> results = concurrently(6, () -> String.valueOf(commands.send(f.owner(), f.room(), request).messageId()));

        assertThat(new HashSet<>(results)).hasSize(1);
        assertThat(messageCount(f)).isEqualTo(1);
    }

    @Test
    void 전송_중_실패하면_메시지와_사진_연결이_함께_롤백되고_다시_보낼_수_있다() throws Exception {
        Fixture f = fixture();
        Long imageId = uploaded(f, f.owner());
        doThrow(new IllegalStateException("snapshot failed")).when(query).snapshot(any());

        assertThatThrownBy(() -> sendPhoto(f, f.owner(), imageId)).isInstanceOf(IllegalStateException.class);

        doCallRealMethod().when(query).snapshot(any());
        assertThat(imageRows.findById(imageId).orElseThrow().getMessageId()).isNull();
        assertThat(messageCount(f)).isZero();
        assertThat(sendPhoto(f, f.owner(), imageId).sequence()).isEqualTo(1);
    }

    @Test
    void 사진_메시지_푸시는_언어별_문구와_집_묶음_키를_보낸다() throws Exception {
        Fixture f = fixture();
        jdbc.update("update users set nickname = ? where id = ?", "사진보낸이", f.owner());
        Long imageId = uploaded(f, f.owner());
        Map<String, String> data = Map.of("type", "CHAT_MESSAGE", "roomId", f.room().toString(),
                "houseId", f.house().toString(), "groupKey", "chat-house-" + f.house());

        sendPhoto(f, f.owner(), imageId);
        verify(fcm, timeout(5_000)).pushWithoutRecord(eq(f.member()), eq("사진 테스트"), eq("사진보낸이: 사진을 보냈어요"), eq(data));

        jdbc.update("update users set language = 'en' where id = ?", f.member());
        sendPhoto(f, f.owner(), uploaded(f, f.owner()));
        verify(fcm, timeout(5_000)).pushWithoutRecord(eq(f.member()), eq("사진 테스트"), eq("사진보낸이: sent a photo"), eq(data));
    }

    @Test
    void 사진_메시지는_AI_사용자_이벤트를_보내지_않고_글_메시지는_보낸다() throws Exception {
        Fixture f = fixture();
        sendPhoto(f, f.owner(), uploaded(f, f.owner()));
        verify(aiEvents, never()).chat(any(), anyLong(), anyLong());

        commands.send(f.owner(), f.room(), new ChatSendRequest(UUID.randomUUID(), "글"));
        verify(aiEvents).chat(any(), anyLong(), anyLong());
    }

    @Test
    void 쿼리_수_측정은_다른_스레드의_조회를_제외한다() throws Exception {
        Fixture f = fixture();
        assertThat(statementsDuring(() -> users.findById(f.owner()))).isPositive();
        try (var executor = Executors.newSingleThreadExecutor()) {
            long counted = statementsDuring(() -> {
                try {
                    executor.submit(() -> users.findById(f.owner())).get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            assertThat(counted).isZero();
        }
    }

    @Test
    void 기록_조회의_쿼리_수는_사진_수와_무관하다() throws Exception {
        Fixture f = fixture();
        for (int i = 0; i < 2; i++) sendPhoto(f, f.owner(), uploaded(f, f.owner()));
        long few = statementsDuring(() -> query.messages(f.member(), f.room(), null, null, 50));

        for (int i = 0; i < 6; i++) sendPhoto(f, f.owner(), uploaded(f, f.owner()));
        long many = statementsDuring(() -> query.messages(f.member(), f.room(), null, null, 50));

        assertThat(many).isEqualTo(few);
    }

    @Test
    void 사진_처리_차례를_얻지_못하면_503으로_응답하고_예약을_남기지_않는다() throws Exception {
        Fixture f = fixture();
        var decoding = (Semaphore) ReflectionTestUtils.getField(PrivatePhotoNormalizer.class, "DECODING");
        int permits = decoding.availablePermits();
        decoding.acquire(permits);
        try {
            error(() -> upload(f, f.owner()), "CHAT_STORAGE_UNAVAILABLE");
        } finally {
            decoding.release(permits);
        }
        assertThat(unsentCount(f.owner())).isZero();
        verify(storage, never()).put(anyString(), any());
    }
}
