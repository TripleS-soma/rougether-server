package com.triples.rougether.userapi.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.chat.entity.ChatImage;
import com.triples.rougether.domain.chat.entity.ChatMessageType;
import com.triples.rougether.domain.chat.repository.ChatImageRepository;
import com.triples.rougether.domain.chat.repository.ChatMessageRepository;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.moderation.entity.ContentReportReason;
import com.triples.rougether.userapi.aiuser.service.AiUserMemoryProjection;
import com.triples.rougether.userapi.aiuser.service.AiUserObserver;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.chat.dto.ChatMessageResponse;
import com.triples.rougether.userapi.chat.dto.ChatSendRequest;
import com.triples.rougether.userapi.chat.service.ChatCleanupJob;
import com.triples.rougether.userapi.chat.service.ChatCleanupTransactions;
import com.triples.rougether.userapi.chat.service.ChatCommandService;
import com.triples.rougether.userapi.chat.service.ChatImageService;
import com.triples.rougether.userapi.chat.service.ChatImageStorage;
import com.triples.rougether.userapi.chat.service.ChatQueryService;
import com.triples.rougether.userapi.global.security.MemberRole;
import com.triples.rougether.userapi.house.service.HouseMemberCommandService;
import com.triples.rougether.userapi.logingift.service.LoginGiftAccessRecorder;
import com.triples.rougether.userapi.member.service.MemberWithdrawalService;
import com.triples.rougether.userapi.moderation.dto.ContentReportRequest;
import com.triples.rougether.userapi.moderation.service.ContentReportService;
import com.triples.rougether.userapi.notification.fcm.FcmPushExecutor;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@AutoConfigureMockMvc
class ChatMessageModerationIntegrationTest {
    private static final String REPORT_BODY = "{\"reason\":\"ABUSE\",\"detail\":\"불쾌해요\"}";

    @Autowired AiUserObserver aiObserver;
    @Autowired AiUserMemoryProjection aiMemory;
    @Autowired ChatCommandService commands;
    @Autowired ChatQueryService query;
    @Autowired ChatImageService images;
    @Autowired ChatCleanupTransactions cleanup;
    @Autowired ChatCleanupJob job;
    @Autowired ChatImageRepository imageRows;
    @Autowired ChatMessageRepository messageRows;
    @Autowired ContentReportService reports;
    @Autowired MemberWithdrawalService withdrawal;
    @Autowired UserRepository users;
    @Autowired HouseRepository houses;
    @Autowired HouseMemberRepository members;
    @Autowired HouseMemberCommandService membershipCommands;
    @Autowired TransactionTemplate tx;
    @Autowired TokenService tokens;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ChatImageStorage storage;
    @MockitoBean FcmPushExecutor fcm;
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
                userIds.addAll(List.of(f.owner(), f.member(), f.outsider()));
                jdbc.update("delete from chat_images where room_id = ?", f.room());
                jdbc.update("delete from chat_messages where room_id = ?", f.room());
                jdbc.update("delete from chat_read_states where room_id = ?", f.room());
                jdbc.update("delete from chat_rooms where id = ?", f.room());
                jdbc.update("delete from house_members where house_id = ?", f.house());
                jdbc.update("delete from house where id = ?", f.house());
            }
            for (Long userId : userIds) {
                jdbc.update("delete from content_reports where reporter_user_id = ?", userId);
                jdbc.update("delete from notification where user_id = ?", userId);
                jdbc.update("delete from user_daily_activity where user_id = ?", userId);
                jdbc.update("delete from users where id = ?", userId);
            }
        });
    }

    Fixture fixture() {
        Fixture fixture = tx.execute(status -> {
            String key = UUID.randomUUID().toString();
            User owner = users.save(User.signUp("chat-mod-owner-" + key + "@test.dev"));
            User member = users.save(User.signUp("chat-mod-member-" + key + "@test.dev"));
            User outsider = users.save(User.signUp("chat-mod-other-" + key + "@test.dev"));
            House house = houses.save(House.create(owner, "운영 테스트", null, null, 4,
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

    MockMultipartFile photo() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(48, 32, BufferedImage.TYPE_INT_RGB), "png", out);
        return new MockMultipartFile("file", "photo.png", "image/png", out.toByteArray());
    }

    Long uploaded(Fixture f, Long user) throws Exception {
        return images.upload(user, f.room(), photo()).imageId();
    }

    ChatMessageResponse sendPhoto(Fixture f, Long user, Long imageId) {
        return commands.send(user, f.room(), new ChatSendRequest(UUID.randomUUID(), null, imageId));
    }

    ChatMessageResponse sendText(Fixture f, Long user, String text) {
        return commands.send(user, f.room(), new ChatSendRequest(UUID.randomUUID(), text));
    }

    ContentReportRequest reason() { return new ContentReportRequest(ContentReportReason.SPAM, null); }

    void deleteMessage(Long messageId) {
        tx.executeWithoutResult(s -> messageRows.findById(messageId).orElseThrow().delete(Instant.now()));
    }

    List<Long> cleanupCandidates() {
        return imageRows.findCleanupCandidates(0, Instant.now(), Instant.now(), PageRequest.of(0, 500));
    }

    String key(Long imageId) { return imageRows.findById(imageId).orElseThrow().getStorageKey(); }

    void error(Runnable runnable, String code) {
        assertThatThrownBy(runnable::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode().code()).isEqualTo(code));
    }

    @Test
    void HTTP로_채팅_메시지를_신고하고_재신고는_처음_신고를_돌려준다() throws Exception {
        Fixture f = fixture();
        Long messageId = sendText(f, f.owner(), "신고 대상").messageId();
        String path = "/api/v1/chat/rooms/" + f.room() + "/messages/" + messageId + "/reports";

        String first = mvc.perform(post(path).header("Authorization", auth(f.member()))
                        .contentType(MediaType.APPLICATION_JSON).content(REPORT_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post(path).header("Authorization", auth(f.member()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SPAM\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportId").value(Long.parseLong(first.replaceAll(".*\"reportId\":(\\d+).*", "$1"))));

        var row = jdbc.queryForMap("select count(*) cnt, max(reason) reason, max(target_type) target_type, "
                + "max(target_owner_user_id) target_owner from content_reports where reporter_user_id = ?", f.member());
        assertThat(((Number) row.get("cnt")).intValue()).isEqualTo(1);
        assertThat(row.get("reason")).isEqualTo("ABUSE");
        assertThat(row.get("target_type")).isEqualTo("CHAT_MESSAGE");
        assertThat(((Number) row.get("target_owner")).longValue()).isEqualTo(f.owner());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(REPORT_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 비구성원_본인_메시지_없는_메시지_다른_방_메시지_삭제된_메시지는_신고할_수_없다() {
        Fixture f = fixture();
        Fixture other = fixture();
        Long messageId = sendText(f, f.owner(), "메시지").messageId();
        Long otherRoomMessage = sendText(other, other.owner(), "다른 방").messageId();
        Long deleted = sendText(f, f.owner(), "지워질 메시지").messageId();
        deleteMessage(deleted);

        error(() -> reports.reportChatMessage(f.outsider(), f.room(), messageId, reason()), "CHAT_FORBIDDEN");
        error(() -> reports.reportChatMessage(f.owner(), f.room(), messageId, reason()), "REPORT_SELF_TARGET");
        error(() -> reports.reportChatMessage(f.member(), f.room(), Long.MAX_VALUE, reason()), "CHAT_MESSAGE_NOT_FOUND");
        error(() -> reports.reportChatMessage(f.member(), f.room(), otherRoomMessage, reason()), "CHAT_MESSAGE_NOT_FOUND");
        error(() -> reports.reportChatMessage(f.member(), f.room(), deleted, reason()), "CHAT_MESSAGE_NOT_FOUND");
        error(() -> reports.reportChatMessage(f.member(), Long.MAX_VALUE, messageId, reason()), "CHAT_ROOM_NOT_FOUND");
        assertThat(jdbc.queryForObject("select count(*) from content_reports where reporter_user_id = ?", Long.class, f.member())).isZero();
    }

    @Test
    void 운영자가_지운_사진_메시지는_문구로_바뀌고_사진이_즉시_막히며_정리된다() throws Exception {
        Fixture f = fixture();
        sendText(f, f.member(), "먼저 보낸 글");
        Long imageId = uploaded(f, f.owner());
        var sent = sendPhoto(f, f.owner(), imageId);
        sendText(f, f.member(), "나중에 보낸 글");
        String key = key(imageId);

        deleteMessage(sent.messageId());

        var items = query.messages(f.member(), f.room(), null, null, 50).items();
        assertThat(items).extracting(ChatMessageResponse::sequence).containsExactly(3L, 2L, 1L);
        var deleted = items.get(1);
        assertThat(deleted.content()).isEqualTo("삭제된 메시지예요");
        assertThat(deleted.image()).isNull();
        assertThat(deleted.messageType()).isEqualTo(ChatMessageType.IMAGE);
        error(() -> images.read(f.member(), imageId), "CHAT_IMAGE_NOT_FOUND");
        assertThat(cleanupCandidates()).contains(imageId);

        job.run();

        verify(storage).delete(key);
        assertThat(imageRows.findById(imageId)).isEmpty();
        assertThat(query.messages(f.member(), f.room(), null, null, 50).items().get(1).content()).isEqualTo("삭제된 메시지예요");
    }

    @Test
    void 사진이_정리된_뒤_같은_요청_ID로_재전송해도_충돌하지_않고_처음_메시지를_돌려준다() throws Exception {
        Fixture f = fixture();
        Long imageId = uploaded(f, f.owner());
        UUID requestId = UUID.randomUUID();
        var sent = commands.send(f.owner(), f.room(), new ChatSendRequest(requestId, null, imageId));
        deleteMessage(sent.messageId());
        cleanup.image(imageId);

        var retried = commands.send(f.owner(), f.room(), new ChatSendRequest(requestId, null, imageId));

        assertThat(retried.messageId()).isEqualTo(sent.messageId());
        assertThat(retried.image()).isNull();
        assertThat(retried.content()).isEqualTo("삭제된 메시지예요");
        // 사진 행이 없어 imageId를 비교할 수 없으므로 다른 값이어도 충돌로 보지 않음.
        var otherId = commands.send(f.owner(), f.room(), new ChatSendRequest(requestId, null, Long.MAX_VALUE));
        assertThat(otherId.messageId()).isEqualTo(sent.messageId());
    }

    @Test
    void 같은_신고자의_동시_재신고는_신고_하나만_만들고_모두_성공한다() throws Exception {
        Fixture f = fixture();
        Long messageId = sendText(f, f.owner(), "동시 신고 대상").messageId();
        var start = new CountDownLatch(1);
        List<Long> ids = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(6)) {
            List<java.util.concurrent.Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return reports.reportChatMessage(f.member(), f.room(), messageId, reason()).reportId();
                }));
            }
            start.countDown();
            for (var future : futures) ids.add(future.get(30, TimeUnit.SECONDS));
        }
        assertThat(new HashSet<>(ids)).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from content_reports where reporter_user_id = ?", Long.class, f.member()))
                .isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 운영자가_지운_메시지는_AI_컨텍스트와_기억_근거에_들어가지_않는다() {
        Fixture f = fixture();
        Long kept = sendText(f, f.owner(), "남는 글").messageId();
        Long hidden = sendText(f, f.owner(), "숨길 글").messageId();
        Object evidenceBefore = ReflectionTestUtils.invokeMethod(aiObserver, "memoryEvidence",
                Map.of("source_id", hidden, "source_type", "CHAT"));
        assertThat(evidenceBefore).isNotNull();

        deleteMessage(hidden);

        var context = (Map<String, Object>) aiObserver.source(f.member(), "CHAT", kept);
        var recent = (List<Map<String, Object>>) context.get("recentMessages");
        assertThat(recent).extracting(m -> m.get("content")).containsExactly("남는 글");
        assertThatThrownBy(() -> aiObserver.source(f.member(), "CHAT", hidden)).isInstanceOf(ResponseStatusException.class);
        Object evidenceAfter = ReflectionTestUtils.invokeMethod(aiObserver, "memoryEvidence",
                Map.of("source_id", hidden, "source_type", "CHAT"));
        assertThat(evidenceAfter).isNull();
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(aiMemory, "fingerprint", "CHAT", hidden))
                .isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);
    }

    @Test
    void 저장_중인_사진은_탈퇴해도_만료_전에는_정리하지_않는다() {
        Fixture f = fixture();
        Instant now = Instant.now();
        Long imageId = tx.execute(s -> imageRows.save(ChatImage.reserve(f.room(), users.getReferenceById(f.member()),
                "private/chat/" + UUID.randomUUID() + ".jpg", 1, 1, now, now.plusSeconds(3600))).getId());

        withdrawal.withdraw(f.member());

        assertThat(cleanupCandidates()).doesNotContain(imageId);
        cleanup.image(imageId);
        assertThat(imageRows.findById(imageId)).isPresent();
    }

    @Test
    void 운영자가_지운_글_메시지는_문구로_바뀌고_원문은_DB에_남는다() {
        Fixture f = fixture();
        var sent = sendText(f, f.owner(), "지워질 원문");

        deleteMessage(sent.messageId());

        var item = query.messages(f.member(), f.room(), null, null, 50).items().getFirst();
        assertThat(item.content()).isEqualTo("삭제된 메시지예요");
        assertThat(item.messageType()).isEqualTo(ChatMessageType.TEXT);
        assertThat(jdbc.queryForObject("select content from chat_messages where id = ?", String.class, sent.messageId()))
                .isEqualTo("지워질 원문");
    }

    @Test
    void 탈퇴한_사람의_사진은_즉시_가려지고_파일은_정리되며_메시지_문구는_유지된다() throws Exception {
        Fixture f = fixture();
        Long imageId = uploaded(f, f.member());
        sendPhoto(f, f.member(), imageId);
        String key = key(imageId);

        withdrawal.withdraw(f.member());

        var item = query.messages(f.owner(), f.room(), null, null, 50).items().getFirst();
        assertThat(item.image()).isNull();
        assertThat(item.content()).isEqualTo("사진을 보냈어요");
        error(() -> images.read(f.owner(), imageId), "CHAT_IMAGE_NOT_FOUND");
        assertThat(cleanupCandidates()).contains(imageId);

        cleanup.image(imageId);

        verify(storage).delete(key);
        assertThat(imageRows.findById(imageId)).isEmpty();
        assertThat(query.messages(f.owner(), f.room(), null, null, 50).items().getFirst().content()).isEqualTo("사진을 보냈어요");
    }

    @Test
    void 해체된_집의_사진은_모두_정리된다() throws Exception {
        Fixture f = fixture();
        Long ownerPhoto = uploaded(f, f.owner());
        Long memberPhoto = uploaded(f, f.member());
        sendPhoto(f, f.owner(), ownerPhoto);
        sendPhoto(f, f.member(), memberPhoto);

        tx.executeWithoutResult(s -> houses.findById(f.house()).orElseThrow().softDelete());

        assertThat(cleanupCandidates()).contains(ownerPhoto, memberPhoto);
        cleanup.image(ownerPhoto);
        cleanup.image(memberPhoto);
        assertThat(imageRows.findById(ownerPhoto)).isEmpty();
        assertThat(imageRows.findById(memberPhoto)).isEmpty();
    }

    @Test
    void 집을_나간_사람이_보낸_사진은_남아서_남은_구성원이_본다() throws Exception {
        Fixture f = fixture();
        Long imageId = uploaded(f, f.member());
        sendPhoto(f, f.member(), imageId);

        membershipCommands.leave(f.member(), f.house());

        assertThat(cleanupCandidates()).doesNotContain(imageId);
        cleanup.image(imageId);
        assertThat(imageRows.findById(imageId)).isPresent();
        assertThat(query.messages(f.owner(), f.room(), null, null, 50).items().getFirst().image()).isNotNull();
        assertThat(images.read(f.owner(), imageId)).isNotEmpty();
    }

    @Test
    void 정상_사진은_지우지_않고_S3_실패는_행을_남겨_재시도한다() throws Exception {
        Fixture f = fixture();
        Long valid = uploaded(f, f.owner());
        sendPhoto(f, f.owner(), valid);
        Long doomed = uploaded(f, f.member());
        sendPhoto(f, f.member(), doomed);
        String doomedKey = key(doomed);
        withdrawal.withdraw(f.member());
        reset(storage);
        doThrow(new RuntimeException("temporary")).when(storage).delete(doomedKey);

        assertThatThrownBy(() -> cleanup.image(doomed)).isInstanceOf(RuntimeException.class);
        assertThat(imageRows.findById(doomed)).isPresent();

        reset(storage);
        cleanup.image(doomed);
        cleanup.image(valid);

        verify(storage).delete(doomedKey);
        verify(storage, never()).delete(key(valid));
        assertThat(imageRows.findById(doomed)).isEmpty();
        assertThat(imageRows.findById(valid)).isPresent();
    }

    @Test
    void 정리와_전송이_경합해도_전송된_사진을_지우지_않는다() throws Exception {
        Fixture f = fixture();
        for (int i = 0; i < 12; i++) {
            Long imageId = uploaded(f, f.owner());
            jdbc.update("update chat_images set created_at = date_sub(utc_timestamp(6), interval 2 day), "
                    + "expires_at = date_add(utc_timestamp(6), interval 25000 microsecond) where id = ?", imageId);
            var start = new CountDownLatch(1);
            boolean sent;
            try (var pool = Executors.newFixedThreadPool(2)) {
                var sending = pool.submit(() -> {
                    start.await();
                    TimeUnit.MILLISECONDS.sleep(20);
                    try {
                        sendPhoto(f, f.owner(), imageId);
                        return true;
                    } catch (BusinessException e) {
                        assertThat(e.getErrorCode().code()).isEqualTo("CHAT_IMAGE_UNAVAILABLE");
                        return false;
                    }
                });
                var cleaning = pool.submit(() -> {
                    start.await();
                    TimeUnit.MILLISECONDS.sleep(26);
                    cleanup.image(imageId);
                    return null;
                });
                start.countDown();
                sent = sending.get(30, TimeUnit.SECONDS);
                cleaning.get(30, TimeUnit.SECONDS);
            }
            var row = imageRows.findById(imageId);
            if (sent) {
                assertThat(row).isPresent();
                assertThat(row.get().getMessageId()).isNotNull();
            } else if (row.isPresent()) {
                assertThat(row.get().getMessageId()).isNull();
            }
        }
    }

    @Test
    void 전송이_사진_행을_잠근_동안_정리는_기다렸다가_연결된_사진을_지우지_않는다() throws Exception {
        Fixture f = fixture();
        Long imageId = uploaded(f, f.owner());
        String key = key(imageId);
        Long messageId = sendText(f, f.owner(), "사진을 연결할 메시지").messageId();
        jdbc.update("update chat_images set created_at = date_sub(utc_timestamp(6), interval 2 day), "
                + "expires_at = date_sub(utc_timestamp(6), interval 1 day) where id = ?", imageId);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var attaching = pool.submit(() -> tx.executeWithoutResult(status -> {
                ChatImage row = imageRows.findForUpdate(imageId).orElseThrow();
                locked.countDown();
                awaitRelease(release);
                row.attach(messageId);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var cleaning = pool.submit(() -> cleanup.image(imageId));
            assertThatThrownBy(() -> cleaning.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            attaching.get(30, TimeUnit.SECONDS);
            cleaning.get(30, TimeUnit.SECONDS);
        }
        verify(storage, never()).delete(key);
        assertThat(imageRows.findById(imageId).orElseThrow().getMessageId()).isEqualTo(messageId);
    }

    @Test
    void 정리가_사진_행을_잠근_동안_전송은_기다렸다가_없어진_사진을_거부한다() throws Exception {
        Fixture f = fixture();
        Long imageId = uploaded(f, f.owner());
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var removing = pool.submit(() -> tx.executeWithoutResult(status -> {
                ChatImage row = imageRows.findForUpdate(imageId).orElseThrow();
                locked.countDown();
                awaitRelease(release);
                imageRows.delete(row);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var sending = pool.submit(() -> sendPhoto(f, f.owner(), imageId));
            assertThatThrownBy(() -> sending.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            removing.get(30, TimeUnit.SECONDS);
            assertThatThrownBy(() -> sending.get(30, TimeUnit.SECONDS)).cause()
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode().code()).isEqualTo("CHAT_IMAGE_UNAVAILABLE"));
        }
        assertThat(jdbc.queryForObject("select count(*) from chat_messages where room_id = ?", Long.class, f.room())).isZero();
    }

    // 단언이 먼저 실패해도 잠금을 쥔 스레드가 영원히 남지 않도록 대기 시간을 둠.
    private static void awaitRelease(CountDownLatch release) {
        try {
            assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 취소가_사진_행을_잠근_동안_전송은_기다렸다가_취소된_사진을_거부한다() throws Exception {
        Fixture f = fixture();
        Long imageId = uploaded(f, f.owner());
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var cancelling = pool.submit(() -> tx.executeWithoutResult(status -> {
                ChatImage row = imageRows.findForUpdate(imageId).orElseThrow();
                locked.countDown();
                awaitRelease(release);
                row.expire(Instant.now());
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var sending = pool.submit(() -> sendPhoto(f, f.owner(), imageId));
            assertThatThrownBy(() -> sending.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            cancelling.get(30, TimeUnit.SECONDS);
            assertThatThrownBy(() -> sending.get(30, TimeUnit.SECONDS)).cause()
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode().code()).isEqualTo("CHAT_IMAGE_UNAVAILABLE"));
        }
        assertThat(jdbc.queryForObject("select count(*) from chat_messages where room_id = ?", Long.class, f.room())).isZero();
    }
}
