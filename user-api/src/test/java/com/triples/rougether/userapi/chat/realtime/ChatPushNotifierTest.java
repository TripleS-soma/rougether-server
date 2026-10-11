package com.triples.rougether.userapi.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.chat.dto.ChatSendRequest;
import com.triples.rougether.userapi.chat.service.ChatCommandService;
import com.triples.rougether.userapi.chat.service.ChatQueryService;
import com.triples.rougether.userapi.notification.dto.NotificationSettingUpdateRequest;
import com.triples.rougether.userapi.notification.fcm.FcmPushExecutor;
import com.triples.rougether.userapi.notification.service.NotificationSettingService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

// 집 채팅 푸시(#435)를 실제 커밋 경계로 검증함(테스트 트랜잭션 없음). FCM 은 mock 으로 호출만 확인함.
@SpringBootTest
class ChatPushNotifierTest {

    // 푸시는 커밋 후 알림 executor 에서 비동기로 나감
    private static final long WAIT = 5_000;
    private static final long QUIET = 1_000;

    @Autowired private ChatCommandService commands;
    @Autowired private ChatQueryService query;
    @Autowired private NotificationSettingService settings;
    @Autowired private UserRepository users;
    @Autowired private HouseRepository houses;
    @Autowired private HouseMemberRepository members;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private FcmPushExecutor fcm;
    @MockitoSpyBean private ChatSocketHandler sockets;

    private final List<Fixture> fixtures = new ArrayList<>();

    record Fixture(Long sender, Long member, Long other, Long outsider, Long house, Long room) {}

    @AfterEach
    void cleanUp() {
        for (Fixture f : fixtures) {
            List<Long> userIds = new ArrayList<>(jdbc.queryForList(
                    "select user_id from house_members where house_id = ?", Long.class, f.house()));
            userIds.add(f.outsider());
            jdbc.update("delete from chat_messages where room_id = ?", f.room());
            jdbc.update("delete from chat_read_states where room_id = ?", f.room());
            jdbc.update("delete from chat_rooms where id = ?", f.room());
            jdbc.update("delete from house_members where house_id = ?", f.house());
            jdbc.update("delete from house where id = ?", f.house());
            for (Long userId : userIds) {
                jdbc.update("delete from notification where user_id = ?", userId);
                jdbc.update("delete from notification_setting where user_id = ?", userId);
                jdbc.update("delete from user_daily_activity where user_id = ?", userId);
                jdbc.update("delete from users where id = ?", userId);
            }
        }
    }

    @Test
    void 새_메시지는_발신자를_뺀_구성원에게_푸시되고_알림함에는_남지_않는다() {
        Fixture f = fixture();

        commands.send(f.sender(), f.room(), new ChatSendRequest(UUID.randomUUID(), "오늘 루틴 다 했어?"));

        Map<String, String> data = Map.of("type", "CHAT_MESSAGE", "roomId", f.room().toString(),
                "houseId", f.house().toString());
        verify(fcm, timeout(WAIT)).pushWithoutRecord(eq(f.member()), eq("우리 집"), eq("보내는이: 오늘 루틴 다 했어?"), eq(data));
        verify(fcm, timeout(WAIT)).pushWithoutRecord(eq(f.other()), eq("우리 집"), eq("보내는이: 오늘 루틴 다 했어?"), eq(data));
        verify(fcm, after(QUIET).never()).pushWithoutRecord(eq(f.sender()), anyString(), anyString(), anyMap());
        verify(fcm, after(QUIET).never()).pushWithoutRecord(eq(f.outsider()), anyString(), anyString(), anyMap());
        assertThat(jdbc.queryForObject("select count(*) from notification where user_id in (?, ?, ?)",
                Integer.class, f.sender(), f.member(), f.other())).isZero();
    }

    @Test
    void 긴_메시지는_앞_100자만_보내고_영어_사용자는_영어_폴백을_쓴다() {
        Fixture f = fixture();
        jdbc.update("update users set nickname = null where id = ?", f.sender());
        jdbc.update("update users set language = 'en' where id = ?", f.other());
        String content = "가".repeat(150);

        commands.send(f.sender(), f.room(), new ChatSendRequest(UUID.randomUUID(), content));

        verify(fcm, timeout(WAIT)).pushWithoutRecord(eq(f.member()), eq("우리 집"), eq("이웃: " + "가".repeat(100) + "…"), anyMap());
        verify(fcm, timeout(WAIT)).pushWithoutRecord(eq(f.other()), eq("우리 집"), eq("A neighbor: " + "가".repeat(100) + "…"),
                anyMap());
    }

    @Test
    void 미리보기는_이모지를_반쪽으로_자르지_않는다() {
        Fixture f = fixture();
        String content = "가".repeat(99) + "😀😀"; // 100번째·101번째 글자가 서로게이트 쌍

        commands.send(f.sender(), f.room(), new ChatSendRequest(UUID.randomUUID(), content));

        verify(fcm, timeout(WAIT)).pushWithoutRecord(eq(f.member()), anyString(),
                eq("보내는이: " + "가".repeat(99) + "😀…"), anyMap());
    }

    @Test
    void 채팅을_보는_중이거나_음소거했거나_설정을_끈_사람은_빠진다() {
        Fixture f = fixture();
        doReturn(true).when(sockets).isWatching(f.member(), f.room());
        commands.changePushMuted(f.other(), f.room(), true);

        commands.send(f.sender(), f.room(), new ChatSendRequest(UUID.randomUUID(), "안녕"));

        verify(fcm, after(QUIET).never()).pushWithoutRecord(anyLong(), anyString(), anyString(), anyMap());
    }

    @Test
    void 채팅_설정이나_마스터를_끄면_푸시가_가지_않는다() {
        Fixture f = fixture();
        settings.updateSettings(f.member(), new NotificationSettingUpdateRequest(null, null, null, null, null, false));
        settings.updateSettings(f.other(), new NotificationSettingUpdateRequest(false, null, null, null, null, true));

        commands.send(f.sender(), f.room(), new ChatSendRequest(UUID.randomUUID(), "안녕"));

        verify(fcm, after(QUIET).never()).pushWithoutRecord(anyLong(), anyString(), anyString(), anyMap());
    }

    @Test
    void 봇_구성원에게는_보내지_않는다() {
        Fixture f = fixture();
        jdbc.update("update users set is_bot = true where id = ?", f.other());

        commands.send(f.sender(), f.room(), new ChatSendRequest(UUID.randomUUID(), "안녕"));

        verify(fcm, timeout(WAIT)).pushWithoutRecord(eq(f.member()), anyString(), anyString(), anyMap());
        verify(fcm, after(QUIET).never()).pushWithoutRecord(eq(f.other()), anyString(), anyString(), anyMap());
    }

    @Test
    void 같은_clientMessageId_재전송은_다시_푸시하지_않는다() {
        Fixture f = fixture();
        ChatSendRequest request = new ChatSendRequest(UUID.randomUUID(), "한 번만");

        commands.send(f.sender(), f.room(), request);
        commands.send(f.sender(), f.room(), request);

        verify(fcm, after(QUIET).times(1)).pushWithoutRecord(eq(f.member()), anyString(), anyString(), anyMap());
    }

    @Test
    void 메시지_저장이_롤백되면_푸시하지_않는다() {
        Fixture f = fixture();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            commands.send(f.sender(), f.room(), new ChatSendRequest(UUID.randomUUID(), "롤백될 메시지"));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);

        verify(fcm, after(QUIET).never()).pushWithoutRecord(anyLong(), anyString(), anyString(), anyMap());
        assertThat(jdbc.queryForObject("select count(*) from chat_messages where room_id = ?", Integer.class,
                f.room())).isZero();
    }

    @Test
    void 푸시가_실패해도_메시지_전송은_성공한다() {
        Fixture f = fixture();
        doThrow(new IllegalStateException("fcm down")).when(fcm)
                .pushWithoutRecord(any(), any(), any(), any());

        var sent = commands.send(f.sender(), f.room(), new ChatSendRequest(UUID.randomUUID(), "그래도 저장"));

        assertThat(sent.sequence()).isPositive();
    }

    @Test
    void 음소거는_기본_꺼짐이고_바꾸면_조회에_반영되며_비구성원은_막힌다() {
        Fixture f = fixture();

        assertThat(query.notification(f.member(), f.room()).muted()).isFalse();
        assertThat(commands.changePushMuted(f.member(), f.room(), true).muted()).isTrue();
        assertThat(query.notification(f.member(), f.room()).muted()).isTrue();
        assertThat(query.notification(f.other(), f.room()).muted()).isFalse(); // 다른 사람에게 영향 없음
        commands.changePushMuted(f.member(), f.room(), false);
        assertThat(query.notification(f.member(), f.room()).muted()).isFalse();

        assertThatThrownBy(() -> query.notification(f.outsider(), f.room())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> commands.changePushMuted(f.outsider(), f.room(), true))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 구독이_없으면_보는_중이_아니다() {
        Fixture f = fixture();
        assertThat(sockets.isWatching(f.member(), f.room())).isFalse();
    }

    private Fixture fixture() {
        Fixture fixture = tx.execute(status -> {
            String key = UUID.randomUUID().toString();
            User sender = users.save(User.signUp("chat-push-sender-" + key + "@test.dev"));
            sender.changeNickname("보내는이");
            User member = users.save(User.signUp("chat-push-member-" + key + "@test.dev"));
            User other = users.save(User.signUp("chat-push-other-" + key + "@test.dev"));
            User outsider = users.save(User.signUp("chat-push-out-" + key + "@test.dev"));
            House house = houses.save(House.create(sender, "우리 집", null, null, 4,
                    key.substring(0, 8), Instant.now().plusSeconds(3600)));
            members.save(HouseMember.create(house, sender, HouseMemberRole.OWNER));
            members.save(HouseMember.create(house, member, HouseMemberRole.MEMBER));
            members.save(HouseMember.create(house, other, HouseMemberRole.MEMBER));
            house.increaseMemberCount();
            house.increaseMemberCount();
            Long room = commands.openHouse(sender.getId(), house.getId()).roomId();
            return new Fixture(sender.getId(), member.getId(), other.getId(), outsider.getId(), house.getId(), room);
        });
        fixtures.add(fixture);
        return fixture;
    }
}
