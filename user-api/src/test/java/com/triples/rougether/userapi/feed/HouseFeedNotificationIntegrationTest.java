package com.triples.rougether.userapi.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.feed.entity.FeedBoardType;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.moderation.entity.UserBlock;
import com.triples.rougether.domain.moderation.repository.UserBlockRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.feed.dto.FeedCreateRequest;
import com.triples.rougether.userapi.feed.dto.FeedRoutineCompletionRequest;
import com.triples.rougether.userapi.feed.dto.FeedUpdateRequest;
import com.triples.rougether.userapi.feed.dto.HouseFeedNotificationRequest;
import com.triples.rougether.userapi.feed.service.FeedCommandService;
import com.triples.rougether.userapi.feed.service.FeedImageStorage;
import com.triples.rougether.userapi.feed.service.HouseFeedNotificationService;
import com.triples.rougether.userapi.feed.service.HouseFeedPostCreated;
import com.triples.rougether.userapi.feed.service.HouseFeedPushNotifier;
import com.triples.rougether.userapi.global.security.MemberRole;
import com.triples.rougether.userapi.notification.dto.NotificationSettingUpdateRequest;
import com.triples.rougether.userapi.notification.fcm.FcmPushExecutor;
import com.triples.rougether.userapi.notification.service.NotificationSettingService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

// 집 피드 새 글 푸시와 집별 알림 설정(#530).
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@RecordApplicationEvents
class HouseFeedNotificationIntegrationTest {
    @Autowired FeedCommandService commands;
    @Autowired HouseFeedNotificationService notificationSettings;
    @Autowired HouseFeedPushNotifier notifier;
    @Autowired NotificationSettingService globalSettings;
    @Autowired UserRepository users;
    @Autowired HouseRepository houses;
    @Autowired HouseMemberRepository members;
    @Autowired UserBlockRepository blocks;
    @Autowired RoutineRepository routines;
    @Autowired RoutineLogRepository routineLogs;
    @Autowired TokenService tokens;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired ApplicationEvents events;
    // 별도 트랜잭션으로 사용자 행을 잠그는 접속 기록은 미커밋 테스트 픽스처와 경합하므로 대체함.
    @MockitoBean com.triples.rougether.userapi.activity.service.UserDailyActivityRecorder activity;
    @MockitoBean com.triples.rougether.userapi.logingift.service.LoginGiftAccessRecorder loginGifts;
    @MockitoBean FeedImageStorage storage;
    @MockitoBean FcmPushExecutor push;

    User owner;
    User member;
    User outsider;
    House home;
    HouseMember membership;

    @BeforeEach void setUp() {
        owner = users.save(User.signUp());
        member = users.save(User.signUp());
        outsider = users.save(User.signUp());
        owner.changeNickname("모카");
        member.changeNickname("솜이");
        home = house(owner, "아침을 여는 집");
        membership = join(home, member);
        em.flush();
    }

    House house(User houseOwner, String name) {
        House house = houses.save(House.createPrivate(houseOwner, name, "소개", "house/cover.png", 6,
                UUID.randomUUID().toString(), Instant.now().minusSeconds(1)));
        members.save(HouseMember.create(house, houseOwner, HouseMemberRole.OWNER));
        return house;
    }
    HouseMember join(House house, User user) {
        house.increaseMemberCount();
        return members.save(HouseMember.create(house, user, HouseMemberRole.MEMBER));
    }
    Long freePost(User author, Long houseId) {
        return commands.create(author.getId(), new FeedCreateRequest(UUID.randomUUID(), "새 글", List.of(),
                FeedBoardType.FREE, null, null, houseId));
    }
    Long verificationPost(User author, String routineTitle) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Routine routine = routines.save(Routine.create(author, null, routineTitle, AuthType.CHECK, "DAILY",
                null, null, null, null));
        routine.assignOriginToSelf();
        routineLogs.save(RoutineLog.complete(routine, today, Instant.now(), CurrencyType.COIN, 0));
        em.flush();
        return commands.create(author.getId(), new FeedCreateRequest(UUID.randomUUID(), "오늘도 완료", List.of(),
                FeedBoardType.VERIFICATION, new FeedRoutineCompletionRequest(routine.getId(), today), null, home.getId()));
    }
    void send(Long postId) {
        em.flush();
        clearInvocations(push);
        notifier.notifyMembers(postId);
    }
    void change(User user, Boolean free, Boolean verification) {
        notificationSettings.change(user.getId(), home.getId(), new HouseFeedNotificationRequest(free, verification));
        em.flush();
    }
    void pushed(User recipient) { verify(push).pushWithoutRecord(eq(recipient.getId()), anyString(), anyString(), any()); }
    void notPushed(User recipient) {
        verify(push, never()).pushWithoutRecord(eq(recipient.getId()), anyString(), anyString(), any());
    }
    String auth(User user) { return "Bearer " + tokens.issueAccessToken(user.getId(), MemberRole.NORMAL); }
    String path(House house) { return "/api/v1/houses/" + house.getId() + "/feed/notification"; }
    void error(Runnable runnable, String code) {
        assertThatThrownBy(runnable::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode().code()).isEqualTo(code));
    }

    @Test void 설정은_기본값으로_시작하고_보낸_값만_집별로_바뀐다() throws Exception {
        House other = house(member, "다른 집");
        em.flush();
        mvc.perform(get(path(home)).header("Authorization", auth(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.houseId").value(home.getId()))
                .andExpect(jsonPath("$.freePostPush").value(true))
                .andExpect(jsonPath("$.verificationPostPush").value(false));
        // 조회만으로는 행을 만들지 않음.
        assertThat(jdbc.queryForObject("select count(*) from house_feed_notification_settings where user_id=?",
                Long.class, member.getId())).isZero();

        mvc.perform(put(path(home)).header("Authorization", auth(member)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationPostPush\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freePostPush").value(true))
                .andExpect(jsonPath("$.verificationPostPush").value(true));
        mvc.perform(put(path(home)).header("Authorization", auth(member)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"freePostPush\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freePostPush").value(false))
                .andExpect(jsonPath("$.verificationPostPush").value(true));
        mvc.perform(get(path(home)).header("Authorization", auth(member)))
                .andExpect(jsonPath("$.freePostPush").value(false))
                .andExpect(jsonPath("$.verificationPostPush").value(true));
        // 다른 집과 다른 구성원의 설정은 그대로임.
        mvc.perform(get(path(other)).header("Authorization", auth(member)))
                .andExpect(jsonPath("$.freePostPush").value(true))
                .andExpect(jsonPath("$.verificationPostPush").value(false));
        mvc.perform(get(path(home)).header("Authorization", auth(owner)))
                .andExpect(jsonPath("$.freePostPush").value(true))
                .andExpect(jsonPath("$.verificationPostPush").value(false));
        assertThat(jdbc.queryForObject("select count(*) from house_feed_notification_settings where house_id=? and user_id=?",
                Long.class, home.getId(), member.getId())).isEqualTo(1);
    }

    @Test void 설정은_구성원만_다루고_빈_요청은_거부한다() throws Exception {
        mvc.perform(put(path(home)).header("Authorization", auth(member)).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FEED_INPUT_INVALID"));
        mvc.perform(get(path(home)).header("Authorization", auth(outsider)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("HOUSE_NOT_MEMBER"));
        mvc.perform(put(path(home)).header("Authorization", auth(outsider)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"freePostPush\":false}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("HOUSE_NOT_MEMBER"));
        mvc.perform(get("/api/v1/houses/" + Long.MAX_VALUE + "/feed/notification").header("Authorization", auth(member)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOUSE_NOT_FOUND"));
        home.softDelete();
        em.flush();
        mvc.perform(get(path(home)).header("Authorization", auth(member)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOUSE_NOT_FOUND"));
        assertThat(jdbc.queryForObject("select count(*) from house_feed_notification_settings", Long.class)).isZero();
    }

    @Test void 집을_나갔다_돌아와도_설정은_그대로_남는다() {
        change(member, false, true);
        membership.leave();
        em.flush();
        error(() -> notificationSettings.get(member.getId(), home.getId()), "HOUSE_NOT_MEMBER");
        error(() -> notificationSettings.change(member.getId(), home.getId(),
                new HouseFeedNotificationRequest(true, null)), "HOUSE_NOT_MEMBER");

        membership.reactivate();
        em.flush();
        var kept = notificationSettings.get(member.getId(), home.getId());
        assertThat(kept.freePostPush()).isFalse();
        assertThat(kept.verificationPostPush()).isTrue();
    }

    @Test void 자유글은_기본으로_알리고_인증글은_켠_사람에게만_알린다() {
        User third = users.save(User.signUp());
        join(home, third);
        change(third, null, true);

        send(freePost(member, home.getId()));
        verify(push).pushWithoutRecord(eq(owner.getId()), eq("아침을 여는 집"), eq("솜이님이 새 글을 올렸어요"),
                eq(Map.of("type", "HOUSE_FEED_POST", "houseId", home.getId().toString(),
                        "postId", lastPostId(member).toString(), "boardType", "FREE")));
        pushed(third);
        notPushed(member);

        send(verificationPost(member, "아침 스트레칭"));
        verify(push).pushWithoutRecord(eq(third.getId()), eq("아침을 여는 집"),
                eq("솜이님이 '아침 스트레칭' 인증글을 올렸어요"), any());
        notPushed(owner);
        notPushed(member);

        // 자유글을 끄면 자유글만 멈추고 인증글 설정은 그대로임.
        change(third, false, null);
        send(freePost(member, home.getId()));
        pushed(owner);
        notPushed(third);
        // 푸시 전용이라 알림함에는 아무것도 남기지 않음.
        assertThat(jdbc.queryForObject("select count(*) from notification where user_id in (?,?,?)", Long.class,
                owner.getId(), member.getId(), third.getId())).isZero();
    }

    Long lastPostId(User author) {
        return jdbc.queryForObject("select max(id) from feed_posts where author_id=?", Long.class, author.getId());
    }

    @Test void 영어_사용자에게는_영어_문구로_보낸다() {
        owner.changePreferences("en", null);
        change(owner, null, true);
        send(freePost(member, home.getId()));
        verify(push).pushWithoutRecord(eq(owner.getId()), eq("아침을 여는 집"), eq("솜이 shared a new post"), any());
        send(verificationPost(member, "Morning stretch"));
        verify(push).pushWithoutRecord(eq(owner.getId()), eq("아침을 여는 집"),
                eq("솜이 shared a check-in for 'Morning stretch'"), any());
    }

    @Test void 닉네임이_없는_작성자는_이웃으로_부른다() {
        User nameless = users.save(User.signUp());
        join(home, nameless);
        member.changePreferences("en", null);
        send(freePost(nameless, home.getId()));
        verify(push).pushWithoutRecord(eq(owner.getId()), eq("아침을 여는 집"), eq("이웃님이 새 글을 올렸어요"), any());
        verify(push).pushWithoutRecord(eq(member.getId()), eq("아침을 여는 집"), eq("A neighbor shared a new post"), any());
    }

    @Test void 받을_수_없거나_받지_않기로_한_구성원은_제외한다() {
        User blocker = users.save(User.signUp());
        User feedOff = users.save(User.signUp());
        User allOff = users.save(User.signUp());
        User left = users.save(User.signUp());
        User withdrawn = users.save(User.signUp());
        User ai = users.save(User.ai("에이아이", "소개"));
        User bot = users.save(User.bot("hfn-" + UUID.randomUUID().toString().substring(0, 12), "봇 주민", "소개"));
        for (User user : List.of(blocker, feedOff, allOff, withdrawn, ai, bot)) join(home, user);
        // 탈퇴 처리와 집 정리 사이에 구성원 행이 ACTIVE 로 남아 있어도 알리지 않음.
        withdrawn.softDelete(Instant.now());
        join(home, left).leave();
        blocks.save(new UserBlock(blocker.getId(), member.getId(), Instant.now()));
        globalSettings.updateSettings(feedOff.getId(), new NotificationSettingUpdateRequest(null, null, null, false, null, null));
        globalSettings.updateSettings(allOff.getId(), new NotificationSettingUpdateRequest(false, null, null, null, null, null));

        send(freePost(member, home.getId()));

        pushed(owner);
        for (User excluded : List.of(member, blocker, feedOff, allOff, left, withdrawn, ai, bot, outsider)) notPushed(excluded);
    }

    @Test void 지워진_글과_해체된_집과_공개_글은_알리지_않는다() {
        Long deleted = freePost(member, home.getId());
        commands.delete(member.getId(), deleted);
        send(deleted);
        verify(push, never()).pushWithoutRecord(anyLong(), anyString(), anyString(), any());

        send(freePost(member, null));
        verify(push, never()).pushWithoutRecord(anyLong(), anyString(), anyString(), any());

        Long post = freePost(member, home.getId());
        home.softDelete();
        send(post);
        verify(push, never()).pushWithoutRecord(anyLong(), anyString(), anyString(), any());
    }

    @Test void 한_사람의_발송_실패가_나머지_구성원의_푸시를_막지_않는다() {
        User third = users.save(User.signUp());
        join(home, third);
        Long post = freePost(member, home.getId());
        em.flush();
        clearInvocations(push);
        doThrow(new IllegalStateException("fcm down")).when(push)
                .pushWithoutRecord(eq(owner.getId()), anyString(), anyString(), any());

        notifier.notifyMembers(post);

        pushed(third);
    }

    @Test void 새로_등록된_집_글에만_푸시_신호를_낸다() {
        var request = new FeedCreateRequest(UUID.randomUUID(), "한 번만 알리는 글", List.of(), FeedBoardType.FREE,
                null, null, home.getId());
        Long post = commands.create(member.getId(), request);
        assertThat(events.stream(HouseFeedPostCreated.class)).containsExactly(new HouseFeedPostCreated(post));

        // 재시도·수정·공개 글은 신호를 더 내지 않음.
        assertThat(commands.create(member.getId(), request)).isEqualTo(post);
        commands.update(member.getId(), post, new FeedUpdateRequest("고친 글"));
        Long photoless = verificationPost(member, "아침 스트레칭");
        commands.update(member.getId(), photoless, new FeedUpdateRequest(null, FeedBoardType.FREE, null));
        freePost(member, null);
        // 인증글 등록 1건이 더해질 뿐, 게시판 전환은 신호를 내지 않음.
        assertThat(events.stream(HouseFeedPostCreated.class))
                .containsExactly(new HouseFeedPostCreated(post), new HouseFeedPostCreated(photoless));

        Long second = freePost(owner, home.getId());
        assertThat(events.stream(HouseFeedPostCreated.class)).containsExactly(new HouseFeedPostCreated(post),
                new HouseFeedPostCreated(photoless), new HouseFeedPostCreated(second));
    }
}
