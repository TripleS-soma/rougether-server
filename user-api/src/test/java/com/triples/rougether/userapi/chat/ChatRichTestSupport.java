package com.triples.rougether.userapi.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.chat.repository.ChatImageRepository;
import com.triples.rougether.domain.chat.repository.ChatMessageRepository;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.aiuser.service.AiUserEvents;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.chat.dto.ChatMessageResponse;
import com.triples.rougether.userapi.chat.dto.ChatSendRequest;
import com.triples.rougether.userapi.chat.service.ChatCleanupJob;
import com.triples.rougether.userapi.chat.service.ChatCommandService;
import com.triples.rougether.userapi.chat.service.ChatImageService;
import com.triples.rougether.userapi.chat.service.ChatImageStorage;
import com.triples.rougether.userapi.chat.service.ChatQueryService;
import com.triples.rougether.userapi.chat.service.ChatReactionService;
import com.triples.rougether.userapi.global.security.MemberRole;
import com.triples.rougether.userapi.global.text.BannedWordChecker;
import com.triples.rougether.userapi.house.service.HouseMemberCommandService;
import com.triples.rougether.userapi.logingift.service.LoginGiftAccessRecorder;
import com.triples.rougether.userapi.member.service.MemberWithdrawalService;
import com.triples.rougether.userapi.notification.fcm.FcmPushExecutor;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

// 사진 여러 장·설명 글·이모티콘·공감 통합 테스트의 공통 픽스처. 하위 테스트가 같은 Spring 컨텍스트를 함께 씀.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.triples.rougether.userapi.chat.ChatRichTestSupport$QueryCounter")
@AutoConfigureMockMvc
abstract class ChatRichTestSupport {
    @Autowired ChatCommandService commands;
    @Autowired ChatReactionService reactions;
    @MockitoSpyBean ChatQueryService query;
    @MockitoSpyBean AiUserEvents aiEvents;
    // 금칙어 시드는 테스트 DB에 없으므로 특정 문장만 금칙어로 판정하게 함.
    @MockitoSpyBean BannedWordChecker bannedWords;
    @MockitoBean FcmPushExecutor fcm;
    @MockitoBean ChatImageStorage storage;
    // 기간 한정 접속 선물이 끼면 실행 날짜에 따라 사용자 정리가 깨지므로 이 테스트에서는 끔.
    @MockitoBean LoginGiftAccessRecorder loginGiftAccess;
    @Autowired ChatImageService images;
    @Autowired ChatCleanupJob job;
    @Autowired ChatImageRepository imageRows;
    @Autowired ChatMessageRepository messageRows;
    @Autowired MemberWithdrawalService withdrawal;
    @Autowired UserRepository users;
    @Autowired HouseRepository houses;
    @Autowired HouseMemberRepository members;
    @Autowired HouseMemberCommandService membershipCommands;
    @Autowired TransactionTemplate tx;
    @Autowired TokenService tokens;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    final List<Fixture> fixtures = new ArrayList<>();
    final Map<String, byte[]> objects = new ConcurrentHashMap<>();

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
                jdbc.update("delete from chat_message_reactions where room_id = ?", f.room());
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
            User owner = users.save(User.signUp("chat-rich-owner-" + key + "@test.dev"));
            owner.changeNickname("보낸이");
            User member = users.save(User.signUp("chat-rich-member-" + key + "@test.dev"));
            User outsider = users.save(User.signUp("chat-rich-other-" + key + "@test.dev"));
            House house = houses.save(House.create(owner, "확장 테스트", null, null, 4,
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

    static MockMultipartFile png(int width, int height) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return new MockMultipartFile("file", "photo.png", "image/png", out.toByteArray());
    }

    // 저장 직후 응답과 DB에서 다시 읽은 응답을 비교함. DB는 시각을 마이크로초까지만 저장하는데
    // Linux JVM의 Instant는 나노초까지라, 시각은 마이크로초로 맞춰 놓고 나머지 필드를 전부 비교함.
    static void assertSameMessage(ChatMessageResponse actual, ChatMessageResponse expected) {
        assertThat(actual).usingRecursiveComparison()
                .withEqualsForType((a, b) -> a.truncatedTo(ChronoUnit.MICROS).equals(b.truncatedTo(ChronoUnit.MICROS)),
                        Instant.class)
                .isEqualTo(expected);
    }

    Long uploaded(Fixture f, Long user) throws Exception {
        return images.upload(user, f.room(), png(48, 32)).imageId();
    }

    List<Long> uploaded(Fixture f, Long user, int count) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) ids.add(uploaded(f, user));
        return ids;
    }

    static ChatSendRequest photos(UUID requestId, String caption, List<Long> imageIds) {
        return new ChatSendRequest(requestId, caption, null, imageIds, null);
    }

    static ChatSendRequest emoticon(UUID requestId, String key) {
        return new ChatSendRequest(requestId, null, null, null, key);
    }

    ChatMessageResponse sendPhotos(Fixture f, Long user, String caption, List<Long> imageIds) {
        return commands.send(user, f.room(), photos(UUID.randomUUID(), caption, imageIds));
    }

    ChatMessageResponse sendText(Fixture f, Long user, String text) {
        return commands.send(user, f.room(), new ChatSendRequest(UUID.randomUUID(), text));
    }

    void deleteMessage(Long messageId) {
        tx.executeWithoutResult(s -> messageRows.findById(messageId).orElseThrow().delete(Instant.now()));
    }

    long messageCount(Fixture f) {
        return jdbc.queryForObject("select count(*) from chat_messages where room_id = ?", Long.class, f.room());
    }

    void error(Runnable runnable, String code) {
        assertThatThrownBy(runnable::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode().code()).isEqualTo(code));
    }

    // 전역 Hibernate 통계에는 비동기 푸시의 SQL도 섞이므로 조회 스레드만 셈.
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
}
