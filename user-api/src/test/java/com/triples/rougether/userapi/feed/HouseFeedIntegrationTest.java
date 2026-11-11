package com.triples.rougether.userapi.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.triples.rougether.domain.moderation.entity.ContentReportReason;
import com.triples.rougether.domain.moderation.entity.UserBlock;
import com.triples.rougether.domain.moderation.repository.UserBlockRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.entity.RoutineLog;
import com.triples.rougether.domain.routine.repository.RoutineLogRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.feed.dto.FeedCommentRequest;
import com.triples.rougether.userapi.feed.dto.FeedCreateRequest;
import com.triples.rougether.userapi.feed.dto.FeedPostResponse;
import com.triples.rougether.userapi.feed.dto.FeedRoutineCompletionRequest;
import com.triples.rougether.userapi.feed.dto.FeedUpdateRequest;
import com.triples.rougether.userapi.feed.service.FeedAuthorHouseCommandService;
import com.triples.rougether.userapi.feed.service.FeedAuthorHouseQueryService;
import com.triples.rougether.userapi.feed.service.FeedCommandService;
import com.triples.rougether.userapi.feed.service.FeedImageStorage;
import com.triples.rougether.userapi.feed.service.FeedImageTransactions;
import com.triples.rougether.userapi.feed.service.FeedQueryService;
import com.triples.rougether.userapi.global.security.MemberRole;
import com.triples.rougether.userapi.moderation.dto.ContentReportRequest;
import com.triples.rougether.userapi.moderation.service.ContentReportService;
import com.triples.rougether.userapi.notification.fcm.FcmPushExecutor;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

// 집 피드(#528): 집 글은 그 집의 ACTIVE 구성원만 읽고 쓰며 공개 피드·비구성원·AI 계정으로 새지 않음.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class HouseFeedIntegrationTest {
    @Autowired FeedCommandService commands;
    @Autowired FeedQueryService query;
    @Autowired FeedImageTransactions images;
    @Autowired com.triples.rougether.userapi.feed.service.FeedCleanupTransactions cleanup;
    @Autowired com.triples.rougether.domain.feed.repository.FeedImageRepository imageRows;
    @Autowired ContentReportService reports;
    @Autowired FeedAuthorHouseQueryService authorHouses;
    @Autowired FeedAuthorHouseCommandService authorHouseJoins;
    @Autowired UserRepository users;
    @Autowired HouseRepository houses;
    @Autowired HouseMemberRepository members;
    @Autowired UserBlockRepository blocks;
    @Autowired RoutineRepository routines;
    @Autowired RoutineLogRepository routineLogs;
    @Autowired com.triples.rougether.domain.minigame.repository.MinigameRunRepository minigameRuns;
    @Autowired com.triples.rougether.domain.minigame.repository.MinigameBestScoreRepository minigameBests;
    @Autowired TokenService tokens;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired com.triples.rougether.userapi.house.service.HouseMemberCommandService houseMembers;
    @Autowired java.time.Clock clock;
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
        home = house(owner);
        membership = join(home, member);
    }

    House house(User houseOwner) {
        House house = houses.save(House.createPrivate(houseOwner, "우리 집", "소개", "house/cover.png", 6,
                UUID.randomUUID().toString(), Instant.now().minusSeconds(1)));
        members.save(HouseMember.create(house, houseOwner, HouseMemberRole.OWNER));
        return house;
    }
    HouseMember join(House house, User user) {
        house.increaseMemberCount();
        return members.save(HouseMember.create(house, user, HouseMemberRole.MEMBER));
    }
    FeedCreateRequest free(String text, Long houseId) {
        return new FeedCreateRequest(UUID.randomUUID(), text, List.of(), FeedBoardType.FREE, null, null, houseId);
    }
    Long housePost(User author, String text) { return commands.create(author.getId(), free(text, home.getId())); }
    Long image(User uploader) {
        var image = images.reserve(uploader.getId(), 64, 64);
        images.complete(uploader.getId(), image.id());
        return image.id();
    }
    FeedCommentRequest comment(String text) { return new FeedCommentRequest(UUID.randomUUID(), text); }
    List<Long> ids(List<FeedPostResponse> items) { return items.stream().map(FeedPostResponse::postId).toList(); }
    List<Long> houseList(User viewer) {
        return ids(query.list(viewer.getId(), null, null, 50, null, home.getId()).items());
    }
    List<Long> publicList(User viewer, Long authorId) {
        return ids(query.list(viewer.getId(), authorId, null, 50, null, null).items());
    }
    long inbox(User recipient) {
        em.flush();
        return jdbc.queryForObject("select count(*) from notification where user_id=? and type='FEED_COMMENT'",
                Long.class, recipient.getId());
    }
    String auth(User user) { return "Bearer " + tokens.issueAccessToken(user.getId(), MemberRole.NORMAL); }
    void error(Runnable runnable, String code) {
        assertThatThrownBy(runnable::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode().code()).isEqualTo(code));
    }

    @Test void 구성원이_올린_집_글은_그_집_목록에만_보이고_공개_피드에는_섞이지_않는다() {
        Long publicPost = commands.create(member.getId(), free("모두에게 보이는 글", null));
        Long post = housePost(member, "우리 집 사람들만 보는 글");

        assertThat(houseList(owner)).containsExactly(post);
        assertThat(query.get(owner.getId(), post).houseId()).isEqualTo(home.getId());
        assertThat(query.get(owner.getId(), publicPost).houseId()).isNull();
        // 구성원 본인에게도 공개 목록과 작성자별 목록에는 집 글이 나오지 않음.
        assertThat(publicList(owner, null)).contains(publicPost).doesNotContain(post);
        assertThat(publicList(owner, member.getId())).containsExactly(publicPost);
        assertThat(publicList(outsider, member.getId())).containsExactly(publicPost);
        // 작성자 필터는 집 목록에서도 조합됨.
        assertThat(ids(query.list(owner.getId(), owner.getId(), null, 50, null, home.getId()).items())).isEmpty();
        assertThat(ids(query.list(owner.getId(), member.getId(), null, 50, FeedBoardType.FREE, home.getId()).items()))
                .containsExactly(post);
    }

    @Test void 집을_직접_지정한_요청은_비구성원과_없는_집을_구분해_거부한다() {
        housePost(member, "집 글");
        error(() -> commands.create(outsider.getId(), free("몰래 쓰기", home.getId())), "HOUSE_NOT_MEMBER");
        error(() -> houseList(outsider), "HOUSE_NOT_MEMBER");
        error(() -> commands.create(member.getId(), free("없는 집", Long.MAX_VALUE)), "HOUSE_NOT_FOUND");
        error(() -> query.list(member.getId(), null, null, 20, null, Long.MAX_VALUE), "HOUSE_NOT_FOUND");
        error(() -> query.list(member.getId(), null, null, 20, null, 0L), "FEED_INPUT_INVALID");
        assertThat(jdbc.queryForObject("select count(*) from feed_posts where author_id=?", Long.class, outsider.getId()))
                .isZero();
    }

    @Test void 해체된_집에는_쓸_수_없고_남은_글도_더_이상_열리지_않는다() {
        Long post = housePost(member, "해체 전 글");
        home.softDelete();
        em.flush();

        error(() -> commands.create(member.getId(), free("해체 뒤 글", home.getId())), "HOUSE_NOT_FOUND");
        error(() -> houseList(member), "HOUSE_NOT_FOUND");
        error(() -> query.get(member.getId(), post), "FEED_POST_NOT_FOUND");
        error(() -> commands.like(member.getId(), post, true), "FEED_POST_NOT_FOUND");
    }

    @Test void 글_ID로_접근하는_모든_경로는_비구성원에게_집_글의_존재를_숨긴다() {
        Long photo = image(member);
        Long post = commands.create(member.getId(), new FeedCreateRequest(UUID.randomUUID(), "사진 있는 집 글",
                List.of(photo), FeedBoardType.FREE, null, null, home.getId()));
        var written = commands.comment(owner.getId(), post, comment("구성원 댓글"));
        Long viewer = outsider.getId();

        error(() -> query.get(viewer, post), "FEED_POST_NOT_FOUND");
        error(() -> query.comments(viewer, post, null, 20), "FEED_POST_NOT_FOUND");
        error(() -> query.replies(viewer, post, written.commentId(), null, 20), "FEED_POST_NOT_FOUND");
        error(() -> commands.like(viewer, post, true), "FEED_POST_NOT_FOUND");
        error(() -> commands.like(viewer, post, false), "FEED_POST_NOT_FOUND");
        error(() -> commands.comment(viewer, post, comment("끼어들기")), "FEED_POST_NOT_FOUND");
        error(() -> commands.deleteComment(viewer, post, written.commentId()), "FEED_POST_NOT_FOUND");
        error(() -> commands.update(viewer, post, new FeedUpdateRequest("고쳐 쓰기")), "FEED_POST_NOT_FOUND");
        error(() -> commands.delete(viewer, post), "FEED_POST_NOT_FOUND");
        error(() -> images.readableKey(viewer, photo), "FEED_IMAGE_NOT_FOUND");
        var report = new ContentReportRequest(ContentReportReason.ABUSE, null);
        error(() -> reports.reportFeedPost(viewer, post, report), "FEED_POST_NOT_FOUND");
        error(() -> reports.reportFeedComment(viewer, post, written.commentId(), report), "FEED_POST_NOT_FOUND");

        // 구성원은 같은 경로를 그대로 씀.
        assertThat(images.readableKey(owner.getId(), photo)).isNotBlank();
        assertThat(query.comments(member.getId(), post, null, 20).items()).hasSize(1);
        commands.like(owner.getId(), post, true);
        assertThat(query.get(member.getId(), post).likeCount()).isEqualTo(1);
        assertThat(reports.reportFeedPost(owner.getId(), post, report)).isNotNull();
        assertThat(reports.reportFeedComment(member.getId(), post, written.commentId(), report)).isNotNull();
    }

    @Test void 집_글에서는_구성원에게도_작성자_집_입주_신청_경로를_열지_않는다() {
        Long publicPost = commands.create(member.getId(), free("공개 글", null));
        Long post = housePost(member, "집 글");

        assertThat(authorHouses.list(outsider.getId(), publicPost).items()).isNotEmpty();
        error(() -> authorHouses.list(owner.getId(), post), "FEED_POST_NOT_FOUND");
        error(() -> authorHouses.list(outsider.getId(), post), "FEED_POST_NOT_FOUND");
        error(() -> authorHouseJoins.join(outsider.getId(), post, home.getId()), "FEED_POST_NOT_FOUND");
        assertThat(members.findByHouseIdAndUserId(home.getId(), outsider.getId())).isEmpty();
    }

    @Test void 인증글도_집에_올릴_수_있고_공개_인증게시판에는_나오지_않는다() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Routine routine = routines.save(Routine.create(member, null, "아침 운동", AuthType.CHECK, "DAILY",
                null, null, null, null));
        routine.assignOriginToSelf();
        routineLogs.save(RoutineLog.complete(routine, today, Instant.now(), CurrencyType.COIN, 0));
        em.flush();

        Long post = commands.create(member.getId(), new FeedCreateRequest(UUID.randomUUID(), "오늘도 완료",
                List.of(image(member)), FeedBoardType.VERIFICATION,
                new FeedRoutineCompletionRequest(routine.getId(), today), null, home.getId()));

        var shown = query.get(owner.getId(), post);
        assertThat(shown.boardType()).isEqualTo(FeedBoardType.VERIFICATION);
        assertThat(shown.routine().title()).isEqualTo("아침 운동");
        assertThat(shown.images()).hasSize(1);
        assertThat(ids(query.list(owner.getId(), null, null, 50, FeedBoardType.VERIFICATION, home.getId()).items()))
                .containsExactly(post);
        assertThat(ids(query.list(owner.getId(), null, null, 50, FeedBoardType.FREE, home.getId()).items())).isEmpty();
        assertThat(ids(query.list(owner.getId(), member.getId(), null, 50, FeedBoardType.VERIFICATION, null).items()))
                .isEmpty();
    }

    @Test void 재시도는_같은_글을_돌려주고_같은_요청_ID를_다른_범위로_보내면_충돌한다() {
        House other = house(member);
        var request = free("한 번만 올라가는 글", home.getId());
        Long post = commands.create(member.getId(), request);

        assertThat(commands.create(member.getId(), request)).isEqualTo(post);
        error(() -> commands.create(member.getId(), new FeedCreateRequest(request.clientPostId(), request.content(),
                List.of(), FeedBoardType.FREE, null, null, other.getId())), "FEED_REQUEST_CONFLICT");
        error(() -> commands.create(member.getId(), new FeedCreateRequest(request.clientPostId(), request.content(),
                List.of(), FeedBoardType.FREE, null, null, null)), "FEED_REQUEST_CONFLICT");
        // 공개 글로 먼저 올린 요청 ID를 집 글로 다시 보내도 범위가 바뀌지 않음.
        var open = free("공개로 올린 글", null);
        Long publicPost = commands.create(member.getId(), open);
        error(() -> commands.create(member.getId(), new FeedCreateRequest(open.clientPostId(), open.content(),
                List.of(), FeedBoardType.FREE, null, null, home.getId())), "FEED_REQUEST_CONFLICT");
        assertThat(query.get(outsider.getId(), publicPost).houseId()).isNull();
        assertThat(houseList(owner)).containsExactly(post);
    }

    @Test void 나간_구성원의_글은_남고_본인은_재입주해야_다시_보고_관리한다() {
        Long post = housePost(member, "나가기 전에 쓴 글");
        var written = commands.comment(member.getId(), post, comment("나가기 전에 쓴 댓글"));
        membership.leave();
        em.flush();

        // 남은 구성원에게는 글과 댓글이 그대로 보임.
        assertThat(houseList(owner)).containsExactly(post);
        assertThat(query.get(owner.getId(), post).author().userId()).isEqualTo(member.getId());
        assertThat(query.comments(owner.getId(), post, null, 20).items()).hasSize(1);
        // 나간 본인은 자기 글도 열거나 고치거나 지울 수 없음.
        error(() -> query.get(member.getId(), post), "FEED_POST_NOT_FOUND");
        error(() -> houseList(member), "HOUSE_NOT_MEMBER");
        error(() -> commands.update(member.getId(), post, new FeedUpdateRequest("나간 뒤 수정")), "FEED_POST_NOT_FOUND");
        error(() -> commands.delete(member.getId(), post), "FEED_POST_NOT_FOUND");
        error(() -> commands.deleteComment(member.getId(), post, written.commentId()), "FEED_POST_NOT_FOUND");
        // 나간 구성원의 재시도가 글 ID를 돌려받지 않음.
        error(() -> commands.create(member.getId(), free("나간 뒤 쓰기", home.getId())), "HOUSE_NOT_MEMBER");

        membership.reactivate();
        em.flush();
        assertThat(houseList(member)).containsExactly(post);
        commands.update(member.getId(), post, new FeedUpdateRequest("돌아와서 고친 글"));
        assertThat(query.get(owner.getId(), post).content()).isEqualTo("돌아와서 고친 글");
        commands.delete(member.getId(), post);
        assertThat(houseList(owner)).isEmpty();
    }

    // #529: 집 글과 그 댓글은 작성자 본인과 그 집의 현재 방장이 지울 수 있고, 수정은 본인만 함.
    @Test void 방장은_남의_집_글과_댓글을_지울_수_있지만_고칠_수는_없다() {
        Long post = housePost(member, "구성원이 쓴 글");
        var written = commands.comment(member.getId(), post, comment("구성원이 쓴 댓글"));
        var reply = commands.comment(owner.getId(), post,
                new FeedCommentRequest(UUID.randomUUID(), "방장이 단 답글", written.commentId()));
        commands.like(owner.getId(), post, true);

        error(() -> commands.update(owner.getId(), post, new FeedUpdateRequest("방장이 고친 글")), "FEED_FORBIDDEN");
        commands.deleteComment(owner.getId(), post, written.commentId());
        // 원댓글은 본문만 비우고 남의 답글은 유지함(본인 삭제와 같은 효과).
        var threads = query.comments(member.getId(), post, null, 20, true).items();
        assertThat(threads).hasSize(1);
        assertThat(threads.getFirst().deleted()).isTrue();
        assertThat(threads.getFirst().content()).isEmpty();
        assertThat(query.replies(member.getId(), post, written.commentId(), null, 20).items())
                .extracting(c -> c.commentId()).containsExactly(reply.commentId());

        commands.delete(owner.getId(), post);
        error(() -> query.get(member.getId(), post), "FEED_POST_NOT_FOUND");
        assertThat(houseList(member)).isEmpty();
        em.flush();
        assertThat(jdbc.queryForObject("select content from feed_posts where id=?", String.class, post)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from feed_comments where post_id=?", Long.class, post)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from feed_likes where post_id=?", Long.class, post)).isZero();
        // 방장이 지워도 작성자에게 알림을 보내지 않음(답글 알림 1건만 남음).
        assertThat(jdbc.queryForObject("select count(*) from notification where user_id=?", Long.class, member.getId()))
                .isEqualTo(1);
    }

    @Test void 일반_구성원은_남의_집_글과_댓글을_지울_수_없다() {
        User other = users.save(User.signUp());
        join(home, other);
        Long ownerPost = housePost(owner, "방장이 쓴 글");
        Long memberPost = housePost(member, "구성원이 쓴 글");
        var written = commands.comment(owner.getId(), memberPost, comment("방장이 단 댓글"));

        error(() -> commands.delete(member.getId(), ownerPost), "FEED_FORBIDDEN");
        error(() -> commands.delete(other.getId(), memberPost), "FEED_FORBIDDEN");
        // 글 작성자라도 자기 글에 달린 남의 댓글은 지울 수 없음(방장만 가능).
        error(() -> commands.deleteComment(member.getId(), memberPost, written.commentId()), "FEED_FORBIDDEN");
        error(() -> commands.deleteComment(other.getId(), memberPost, written.commentId()), "FEED_FORBIDDEN");
        assertThat(houseList(owner)).containsExactly(memberPost, ownerPost);
    }

    @Test void 방장_권한은_공개_글과_다른_집_글에는_미치지_않는다() {
        Long publicPost = commands.create(member.getId(), free("구성원이 쓴 공개 글", null));
        var publicComment = commands.comment(member.getId(), publicPost, comment("공개 글 댓글"));
        House otherHome = house(member);
        join(otherHome, owner);
        Long elsewhere = commands.create(member.getId(), free("다른 집에 쓴 글", otherHome.getId()));

        error(() -> commands.delete(owner.getId(), publicPost), "FEED_FORBIDDEN");
        error(() -> commands.deleteComment(owner.getId(), publicPost, publicComment.commentId()), "FEED_FORBIDDEN");
        // 다른 집에서는 일반 구성원일 뿐이라 그 집 글을 지울 수 없음.
        error(() -> commands.delete(owner.getId(), elsewhere), "FEED_FORBIDDEN");
        assertThat(query.get(owner.getId(), publicPost).deletable()).isFalse();
        assertThat(query.get(owner.getId(), elsewhere).deletable()).isFalse();
        assertThat(query.get(member.getId(), elsewhere).deletable()).isTrue();
    }

    @Test void 나가거나_강퇴된_전_방장과_해체된_집의_방장은_아무_글도_지울_수_없다() {
        Long post = housePost(member, "구성원이 쓴 글");
        var written = commands.comment(member.getId(), post, comment("구성원이 쓴 댓글"));
        HouseMember ownerMembership = members.findByHouseIdAndUserId(home.getId(), owner.getId()).orElseThrow();

        // 방장 역할이 남은 채 나가거나 강퇴돼도 구성원이 아니므로 글이 없는 것처럼 응답함.
        ownerMembership.leave();
        em.flush();
        error(() -> commands.delete(owner.getId(), post), "FEED_POST_NOT_FOUND");
        error(() -> commands.deleteComment(owner.getId(), post, written.commentId()), "FEED_POST_NOT_FOUND");
        ownerMembership.kick();
        em.flush();
        error(() -> commands.delete(owner.getId(), post), "FEED_POST_NOT_FOUND");
        assertThat(query.get(member.getId(), post).content()).isEqualTo("구성원이 쓴 글");

        ownerMembership.reactivate();
        home.softDelete();
        em.flush();
        error(() -> commands.delete(owner.getId(), post), "FEED_POST_NOT_FOUND");
        error(() -> commands.deleteComment(owner.getId(), post, written.commentId()), "FEED_POST_NOT_FOUND");
        assertThat(jdbc.queryForObject("select count(*) from feed_posts where id=? and deleted_at is null", Long.class, post))
                .isEqualTo(1);
    }

    @Test void 방장이_바뀌면_새_방장만_남의_글을_지울_수_있다() {
        Long post = housePost(owner, "옛 방장이 쓴 글");
        User other = users.save(User.signUp());
        join(home, other);
        Long otherPost = housePost(other, "다른 구성원이 쓴 글");
        HouseMember ownerMembership = members.findByHouseIdAndUserId(home.getId(), owner.getId()).orElseThrow();
        ownerMembership.demoteToMember();
        membership.promoteToOwner();
        em.flush();

        error(() -> commands.delete(owner.getId(), otherPost), "FEED_FORBIDDEN");
        assertThat(query.get(owner.getId(), otherPost).deletable()).isFalse();
        assertThat(query.get(member.getId(), otherPost).deletable()).isTrue();
        commands.delete(member.getId(), otherPost);
        commands.delete(member.getId(), post);
        assertThat(houseList(other)).isEmpty();
    }

    @Test void 응답의_deletable은_본인과_방장에게만_참이다() {
        User other = users.save(User.signUp());
        join(home, other);
        Long post = housePost(member, "구성원이 쓴 글");
        var written = commands.comment(member.getId(), post, comment("구성원이 쓴 댓글"));
        var removed = commands.comment(other.getId(), post, comment("곧 지울 댓글"));
        commands.comment(owner.getId(), post, new FeedCommentRequest(UUID.randomUUID(), "남는 답글", removed.commentId()));
        commands.deleteComment(other.getId(), post, removed.commentId());

        assertThat(query.get(member.getId(), post).deletable()).isTrue();
        assertThat(query.get(owner.getId(), post).deletable()).isTrue();
        assertThat(query.get(other.getId(), post).deletable()).isFalse();
        assertThat(query.get(owner.getId(), post).mine()).isFalse();
        // 목록도 상세와 같은 값을 줌.
        assertThat(query.list(owner.getId(), null, null, 20, null, home.getId()).items().getFirst().deletable()).isTrue();
        assertThat(query.list(other.getId(), null, null, 20, null, home.getId()).items().getFirst().deletable()).isFalse();

        var forOwner = query.comments(owner.getId(), post, null, 20, true).items();
        var forOther = query.comments(other.getId(), post, null, 20, true).items();
        var forMember = query.comments(member.getId(), post, null, 20, true).items();
        assertThat(forOwner.get(0).commentId()).isEqualTo(written.commentId());
        assertThat(forOwner.get(0).deletable()).isTrue();
        assertThat(forOwner.get(0).mine()).isFalse();
        assertThat(forMember.get(0).deletable()).isTrue();
        assertThat(forOther.get(0).deletable()).isFalse();
        // 이미 지워진 댓글 자리는 방장에게도 지울 수 없는 것으로 표시함.
        assertThat(forOwner.get(1).deleted()).isTrue();
        assertThat(forOwner.get(1).deletable()).isFalse();
        // 방장은 남의 답글도, 본인은 자기 답글을 지울 수 있음.
        assertThat(query.replies(owner.getId(), post, removed.commentId(), null, 20).items().getFirst().deletable()).isTrue();
        assertThat(query.replies(other.getId(), post, removed.commentId(), null, 20).items().getFirst().deletable()).isFalse();
        // 등록 응답도 같은 규칙임.
        assertThat(commands.comment(other.getId(), post, comment("새 댓글")).deletable()).isTrue();

        // 공개 글에서는 본인만 참임.
        Long publicPost = commands.create(member.getId(), free("공개 글", null));
        assertThat(query.get(member.getId(), publicPost).deletable()).isTrue();
        assertThat(query.get(owner.getId(), publicPost).deletable()).isFalse();
    }

    @Test void 나간_구성원은_자기가_올린_사진도_열지_못하고_집_글을_신고할_수도_없다() {
        Long photo = image(member);
        Long post = commands.create(member.getId(), new FeedCreateRequest(UUID.randomUUID(), "사진 있는 집 글",
                List.of(photo), FeedBoardType.FREE, null, null, home.getId()));
        Long ownerPost = housePost(owner, "방장이 쓴 글");
        var written = commands.comment(owner.getId(), ownerPost, comment("방장 댓글"));
        var report = new ContentReportRequest(ContentReportReason.SPAM, null);
        assertThat(images.readableKey(member.getId(), photo)).isNotBlank();
        membership.leave();
        em.flush();

        error(() -> images.readableKey(member.getId(), photo), "FEED_IMAGE_NOT_FOUND");
        error(() -> reports.reportFeedPost(member.getId(), ownerPost, report), "FEED_POST_NOT_FOUND");
        error(() -> reports.reportFeedComment(member.getId(), ownerPost, written.commentId(), report), "FEED_POST_NOT_FOUND");
        // 남은 구성원은 나간 사람이 올린 사진을 계속 봄.
        assertThat(images.readableKey(owner.getId(), photo)).isNotBlank();

        home.softDelete();
        em.flush();
        error(() -> images.readableKey(owner.getId(), photo), "FEED_IMAGE_NOT_FOUND");
        error(() -> reports.reportFeedPost(owner.getId(), post, report), "FEED_POST_NOT_FOUND");
    }

    // 나간 뒤에는 본인도 집 글을 지울 수 없으므로, 나가는 시점에 "내 글도 지우기"를 고를 수 있음.
    @Test void 나가면서_내_글도_지우기를_고르면_그_집에_남긴_글과_댓글만_지운다() {
        Long mine = housePost(member, "나가면서 지울 글");
        Long ownerPost = housePost(owner, "방장이 쓴 글");
        var myComment = commands.comment(member.getId(), ownerPost, comment("나가면서 지울 댓글"));
        var reply = commands.comment(owner.getId(), ownerPost,
                new FeedCommentRequest(UUID.randomUUID(), "방장이 단 답글", myComment.commentId()));
        commands.comment(owner.getId(), mine, comment("내 글에 달린 남의 댓글"));
        House other = house(member);
        Long elsewhere = commands.create(member.getId(), free("다른 집에 쓴 글", other.getId()));
        Long open = commands.create(member.getId(), free("공개 글", null));
        var openComment = commands.comment(member.getId(), open, comment("공개 글 댓글"));
        em.flush();
        em.clear();

        houseMembers.leave(member.getId(), home.getId(), true);
        em.flush();
        em.clear();

        // 이 집에 쓴 글은 삭제된 글이 되고 남은 구성원 목록에서 빠짐.
        assertThat(houseList(owner)).containsExactly(ownerPost);
        error(() -> query.get(owner.getId(), mine), "FEED_POST_NOT_FOUND");
        assertThat(jdbc.queryForObject("select count(*) from feed_posts where id=? and content='' and deleted_at is not null",
                Long.class, mine)).isEqualTo(1);
        // 남의 글에 단 댓글은 본문만 비우고 그 아래 남의 답글은 유지함.
        var threads = query.comments(owner.getId(), ownerPost, null, 20, true).items();
        assertThat(threads).hasSize(1);
        assertThat(threads.getFirst().deleted()).isTrue();
        assertThat(threads.getFirst().content()).isEmpty();
        assertThat(query.replies(owner.getId(), ownerPost, myComment.commentId(), null, 20).items())
                .extracting(c -> c.commentId()).containsExactly(reply.commentId());
        // 다른 집 글과 공개 글·댓글은 그대로임.
        assertThat(query.get(member.getId(), elsewhere).content()).isEqualTo("다른 집에 쓴 글");
        assertThat(query.get(outsider.getId(), open).content()).isEqualTo("공개 글");
        assertThat(query.comments(outsider.getId(), open, null, 20).items())
                .extracting(c -> c.commentId()).containsExactly(openComment.commentId());
        // 지운 글에 달려 있던 남의 댓글은 이어지는 정리 작업이 치움.
        cleanup.withdrawnContent();
        assertThat(jdbc.queryForObject("select count(*) from feed_comments where post_id=? and (content<>'' or deleted_at is null)",
                Long.class, mine)).isZero();
    }

    @Test void 고르지_않고_나가면_글과_댓글은_그대로_남는다() {
        Long mine = housePost(member, "남겨 둘 글");
        Long ownerPost = housePost(owner, "방장이 쓴 글");
        commands.comment(member.getId(), ownerPost, comment("남겨 둘 댓글"));
        em.flush();
        em.clear();

        houseMembers.leave(member.getId(), home.getId());
        em.flush();
        em.clear();

        assertThat(houseList(owner)).containsExactly(ownerPost, mine);
        assertThat(query.get(owner.getId(), mine).content()).isEqualTo("남겨 둘 글");
        assertThat(query.comments(owner.getId(), ownerPost, null, 20).items()).hasSize(1);
        error(() -> query.get(member.getId(), mine), "FEED_POST_NOT_FOUND");
    }

    @Test void 나가기_API는_선택값을_받아_내_글을_지운다() throws Exception {
        Long mine = housePost(member, "화면에서 나가며 지울 글");
        em.flush();
        em.clear();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/houses/" + home.getId() + "/members/me").param("deleteFeedContent", "true")
                        .header("Authorization", auth(member)))
                .andExpect(status().isNoContent());
        em.flush();
        em.clear();

        assertThat(houseList(owner)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from feed_posts where id=? and deleted_at is not null", Long.class, mine))
                .isEqualTo(1);
    }

    @Test void 강퇴된_구성원도_글은_남기고_접근만_잃는다() {
        Long post = housePost(member, "강퇴 전에 쓴 글");
        membership.kick();
        em.flush();

        assertThat(houseList(owner)).containsExactly(post);
        error(() -> query.get(member.getId(), post), "FEED_POST_NOT_FOUND");
        error(() -> commands.comment(member.getId(), post, comment("강퇴 뒤 댓글")), "FEED_POST_NOT_FOUND");
    }

    @Test void 새로_입주한_구성원은_입주_전에_올라온_글도_본다() {
        Long before = housePost(owner, "입주 전 글");
        error(() -> query.get(outsider.getId(), before), "FEED_POST_NOT_FOUND");

        join(home, outsider);
        em.flush();

        assertThat(houseList(outsider)).containsExactly(before);
        commands.comment(outsider.getId(), before, comment("이제 댓글도 달 수 있음"));
        assertThat(query.get(owner.getId(), before).commentCount()).isEqualTo(1);
    }

    @Test void 차단한_구성원의_집_글은_집_안에서도_숨긴다() {
        Long post = housePost(member, "차단당한 사람이 쓴 글");
        blocks.save(new UserBlock(owner.getId(), member.getId(), Instant.now()));
        em.flush();

        assertThat(houseList(owner)).isEmpty();
        error(() -> query.get(owner.getId(), post), "FEED_POST_NOT_FOUND");
    }

    @Test void 집_글_댓글_알림은_지금_그_집에_있는_사람에게만_간다() {
        Long post = housePost(owner, "방장이 쓴 글");
        var first = commands.comment(member.getId(), post, comment("구성원 댓글"));
        assertThat(inbox(owner)).isEqualTo(1);

        // 답글 대상이 집을 나갔으면 알리지 않고, 글 작성자에게만 알림.
        membership.leave();
        em.flush();
        User newcomer = users.save(User.signUp());
        join(home, newcomer);
        em.flush();
        commands.comment(newcomer.getId(), post,
                new FeedCommentRequest(UUID.randomUUID(), "나간 사람에게 단 답글", first.commentId()));
        assertThat(inbox(member)).isZero();
        assertThat(inbox(owner)).isEqualTo(2);

        // 글 작성자가 집을 나갔으면 새 댓글을 알리지 않음.
        membership.reactivate();
        em.flush();
        Long written = housePost(member, "구성원이 쓴 글");
        membership.leave();
        em.flush();
        commands.comment(owner.getId(), written, comment("작성자가 나간 글의 댓글"));
        assertThat(inbox(member)).isZero();
    }

    @Test void 집_글_댓글도_수신자가_차단한_사람의_댓글은_알리지_않는다() {
        Long post = housePost(owner, "방장이 쓴 글");
        blocks.save(new UserBlock(owner.getId(), member.getId(), Instant.now()));
        em.flush();

        commands.comment(member.getId(), post, comment("차단당한 사람이 단 댓글"));
        assertThat(inbox(owner)).isZero();
    }

    @Test void 집_자유글에도_미니게임_기록을_붙일_수_있다() {
        Instant playedAt = Instant.parse("2026-10-14T03:00:00Z");
        String tower = com.triples.rougether.userapi.minigame.service.MinigameCatalog.TOWER;
        var run = com.triples.rougether.domain.minigame.entity.MinigameRun.start(UUID.randomUUID().toString(), member,
                tower, 3, 1, playedAt.minusSeconds(60), playedAt.plusSeconds(1800));
        minigameBests.save(com.triples.rougether.domain.minigame.entity.MinigameBestScore
                .create(member, tower, 3, 12_345, playedAt));
        run.finish(100, 12_345, "h".repeat(64), 12_345, true, 1, playedAt);
        String runId = minigameRuns.save(run).getId();
        em.flush();

        Long post = commands.create(member.getId(), new FeedCreateRequest(UUID.randomUUID(), "우리 집에 자랑하기",
                List.of(), FeedBoardType.FREE, null,
                new com.triples.rougether.userapi.feed.dto.FeedMinigameRecordRequest(runId), home.getId()));

        var shown = query.get(owner.getId(), post);
        assertThat(shown.houseId()).isEqualTo(home.getId());
        assertThat(shown.minigameRecord().score()).isEqualTo(12_345);
        error(() -> query.get(outsider.getId(), post), "FEED_POST_NOT_FOUND");
    }

    // #531: 해체된 집의 글은 정리 작업이 삭제된 글로 만들고 댓글·좋아요·사진을 치움.
    @Test void 집이_해체되면_정리_작업이_그_집_글과_댓글_좋아요_사진을_치운다() {
        Long photo = image(member);
        Long post = commands.create(member.getId(), new FeedCreateRequest(UUID.randomUUID(), "해체될 집의 글",
                List.of(photo), FeedBoardType.FREE, null, null, home.getId()));
        commands.comment(owner.getId(), post, comment("해체될 집의 댓글"));
        commands.like(owner.getId(), post, true);
        House other = house(member);
        Long kept = commands.create(member.getId(), free("다른 집의 글", other.getId()));
        Long open = commands.create(member.getId(), free("공개 글", null));
        commands.comment(member.getId(), kept, comment("다른 집의 댓글"));
        commands.like(member.getId(), open, true);
        // 해체 전에는 정리 대상이 아님.
        em.flush();
        assertThat(cleanup.dissolvedHousePosts()).isZero();

        home.softDelete();
        em.flush();
        assertThat(cleanup.dissolvedHousePosts()).isEqualTo(1);
        cleanup.withdrawnContent();
        em.clear();

        var erased = jdbc.queryForMap("select content, deleted_at from feed_posts where id=?", post);
        assertThat(erased.get("content")).isEqualTo("");
        assertThat(erased.get("deleted_at")).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from feed_comments where post_id=? and (content<>'' or deleted_at is null)",
                Long.class, post)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from feed_likes where post_id=?", Long.class, post)).isZero();
        // 사진은 삭제된 글의 사진으로 정리 대상이 되고 저장소와 행이 함께 지워짐.
        assertThat(imageRows.findCleanupCandidates(0, Instant.now(), org.springframework.data.domain.PageRequest.of(0, 100)))
                .contains(photo);
        cleanup.image(photo);
        assertThat(imageRows.findById(photo)).isEmpty();
        // 다른 집 글과 공개 글은 그대로임.
        assertThat(query.get(member.getId(), kept).content()).isEqualTo("다른 집의 글");
        assertThat(query.get(member.getId(), kept).commentCount()).isEqualTo(1);
        assertThat(query.get(outsider.getId(), open).likeCount()).isEqualTo(1);
        // 다시 실행해도 결과가 같고 삭제 시각을 덮어쓰지 않음.
        assertThat(cleanup.dissolvedHousePosts()).isZero();
        cleanup.withdrawnContent();
        assertThat(jdbc.queryForMap("select content, deleted_at from feed_posts where id=?", post)).isEqualTo(erased);
    }

    // 스케줄 작업은 테스트에서 꺼 두므로 직접 만들어 한 번 돌림. 단계 순서가 바뀌면 댓글·좋아요·사진이 남아 실패함.
    @Test void 정리_작업_한_번이_해체된_집의_글부터_사진_저장소까지_순서대로_치운다() {
        Long photo = image(member);
        String key = imageRows.findById(photo).orElseThrow().getStorageKey();
        Long post = commands.create(member.getId(), new FeedCreateRequest(UUID.randomUUID(), "해체될 집의 글",
                List.of(photo), FeedBoardType.FREE, null, null, home.getId()));
        commands.comment(owner.getId(), post, comment("해체될 집의 댓글"));
        commands.like(owner.getId(), post, true);
        Long open = commands.create(member.getId(), free("공개 글", null));
        home.softDelete();
        // 운영에서는 단계마다 트랜잭션이 달라 매번 새로 읽음. 테스트는 한 트랜잭션이라 읽어 둔 글을 비워 같은 조건을 만듦.
        em.flush();
        em.clear();

        new com.triples.rougether.userapi.feed.service.FeedCleanupJob(cleanup, imageRows, clock).run();
        em.clear();

        assertThat(jdbc.queryForObject("select count(*) from feed_posts where id=? and content='' and deleted_at is not null",
                Long.class, post)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from feed_comments where post_id=?", Long.class, post)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from feed_comments where post_id=? and content='' and deleted_at is not null",
                Long.class, post)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from feed_likes where post_id=?", Long.class, post)).isZero();
        assertThat(imageRows.findById(photo)).isEmpty();
        org.mockito.Mockito.verify(storage).delete(key);
        assertThat(query.get(outsider.getId(), open).content()).isEqualTo("공개 글");
    }

    @Test void 방장이_탈퇴해_승계된_집의_글은_정리하지_않는다() {
        Long post = housePost(member, "승계된 집의 글");
        members.findByHouseIdAndUserId(home.getId(), owner.getId()).orElseThrow().leave();
        membership.promoteToOwner();
        em.flush();

        assertThat(cleanup.dissolvedHousePosts()).isZero();
        assertThat(query.get(member.getId(), post).content()).isEqualTo("승계된 집의 글");
    }

    @Test void 봇과_AI_계정은_구성원이어도_집_피드에_참여하지_못한다() {
        Long post = housePost(member, "사람끼리 보는 글");
        User ai = users.save(User.ai("에이아이", "소개"));
        User bot = users.save(User.bot("hf-" + UUID.randomUUID().toString().substring(0, 12), "봇 주민", "소개"));
        join(home, ai);
        join(home, bot);
        em.flush();

        error(() -> query.get(ai.getId(), post), "FEED_POST_NOT_FOUND");
        error(() -> houseList(ai), "HOUSE_NOT_MEMBER");
        error(() -> commands.create(ai.getId(), free("AI가 쓴 집 글", home.getId())), "HOUSE_NOT_MEMBER");
        error(() -> commands.comment(ai.getId(), post, comment("AI 댓글")), "FEED_POST_NOT_FOUND");
        error(() -> commands.like(ai.getId(), post, true), "FEED_POST_NOT_FOUND");
        // 봇 계정은 피드 자체에 접근하지 못함(기존 규칙).
        error(() -> query.get(bot.getId(), post), "AUTH_INVALID_TOKEN");
        assertThat(jdbc.queryForObject("select count(*) from feed_posts where house_id=?", Long.class, home.getId()))
                .isEqualTo(1);
    }

    @Test void 컨트롤러는_houseId를_받아_집_글을_등록하고_조회한다() throws Exception {
        String body = mapper.writeValueAsString(free("화면에서 올린 집 글", home.getId()));
        var created = mvc.perform(post("/api/v1/feed/posts").header("Authorization", auth(member))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.houseId").value(home.getId()))
                .andExpect(jsonPath("$.boardType").value("FREE")).andReturn().getResponse();
        long post = mapper.readTree(created.getContentAsString()).path("postId").asLong();

        mvc.perform(get("/api/v1/feed/posts").param("houseId", home.getId().toString())
                        .header("Authorization", auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].postId").value(post))
                .andExpect(jsonPath("$.items[0].houseId").value(home.getId()));
        mvc.perform(get("/api/v1/feed/posts").header("Authorization", auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.postId == " + post + ")]").isEmpty());
        mvc.perform(get("/api/v1/feed/posts").param("houseId", home.getId().toString())
                        .header("Authorization", auth(outsider)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("HOUSE_NOT_MEMBER"));
        mvc.perform(get("/api/v1/feed/posts/" + post).header("Authorization", auth(outsider)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FEED_POST_NOT_FOUND"));
        mvc.perform(get("/api/v1/feed/posts").param("houseId", "0").header("Authorization", auth(owner)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/feed/posts").header("Authorization", auth(outsider))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(free("남의 집", home.getId()))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("HOUSE_NOT_MEMBER"));
        mvc.perform(post("/api/v1/feed/posts").header("Authorization", auth(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientPostId\":\"" + UUID.randomUUID() + "\",\"content\":\"잘못된 집\",\"boardType\":\"FREE\",\"houseId\":0}"))
                .andExpect(status().isBadRequest());
    }
}
