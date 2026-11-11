package com.triples.rougether.userapi.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.triples.rougether.domain.feed.entity.FeedBoardType;
import com.triples.rougether.domain.feed.entity.FeedPost;
import com.triples.rougether.domain.feed.repository.FeedPostRepository;
import com.triples.rougether.domain.house.entity.*;
import com.triples.rougether.domain.house.repository.*;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.moderation.entity.UserBlock;
import com.triples.rougether.domain.moderation.repository.UserBlockRepository;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.MemberRole;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FeedAuthorHouseIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TokenService tokens;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired FeedPostRepository posts;
    @Autowired HouseRepository houses;
    @Autowired HouseMemberRepository members;
    @Autowired HouseJoinRequestRepository requests;
    @Autowired UserBlockRepository blocks;
    @Autowired JdbcTemplate jdbc;
    // 별도 트랜잭션으로 사용자 행을 잠그는 접속 기록은 미커밋 테스트 픽스처와 경합하므로 대체함.
    @MockitoBean com.triples.rougether.userapi.activity.service.UserDailyActivityRecorder activity;
    @MockitoBean com.triples.rougether.userapi.logingift.service.LoginGiftAccessRecorder loginGifts;
    User author;
    User viewer;
    FeedPost feedPost;
    House home;

    @BeforeEach void setUp() {
        author = users.save(User.signUp());
        viewer = users.save(User.signUp());
        feedPost = posts.save(FeedPost.create(author, UUID.randomUUID().toString(), "a".repeat(64), "함께 살아요", FeedBoardType.FREE));
        home = house(author, false, 4);
    }

    House house(User owner, boolean isPublic, Integer maxMembers) {
        House house = houses.save(isPublic
                ? House.create(owner, "공개 집", "소개", "house/cover.png", maxMembers, UUID.randomUUID().toString(), Instant.now().minusSeconds(1))
                : House.createPrivate(owner, "비공개 집", "소개", "house/cover.png", maxMembers, UUID.randomUUID().toString(), Instant.now().minusSeconds(1)));
        members.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        return house;
    }

    HouseMember addMember(House house, User user) {
        house.increaseMemberCount();
        return members.save(HouseMember.create(house, user, HouseMemberRole.MEMBER));
    }

    String path() { return "/api/v1/feed/posts/" + feedPost.getId() + "/houses"; }
    String joinPath(House house) { return path() + "/" + house.getId() + "/join"; }
    String auth(User user) { return "Bearer " + tokens.issueAccessToken(user.getId(), MemberRole.NORMAL); }

    @Test void 공개_API_문서에서_작성자_집과_기존_집_탐색의_스키마를_구분한다() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.FeedAuthorHouseListResponse.properties.items.items['$ref']")
                        .value("#/components/schemas/FeedAuthorHouseSummary"))
                .andExpect(jsonPath("$.components.schemas.FeedAuthorHouseSummary.properties.requiresApproval").exists())
                .andExpect(jsonPath("$.components.schemas.FeedAuthorHouseSummary.properties.canJoin").exists())
                .andExpect(jsonPath("$.components.schemas.HouseSummary.properties.goals").exists())
                .andExpect(jsonPath("$.components.schemas.HouseSummary.properties.requiresApproval").doesNotExist());
    }

    @Test void 비공개_집도_코드나_승인_없이_즉시입주하고_기존_신청은_종결한다() throws Exception {
        home.changeOnboardingAutoJoinEnabled(true);
        var pending = requests.save(HouseJoinRequest.create(home, viewer));
        mvc.perform(get(path()).header("Authorization", auth(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].houseId").value(home.getId()))
                .andExpect(jsonPath("$.items[0].isPublic").value(false))
                .andExpect(jsonPath("$.items[0].canJoin").value(true))
                .andExpect(jsonPath("$.items[0].myJoinRequestStatus").value("PENDING"))
                .andExpect(jsonPath("$.items[0].inviteCode").doesNotExist())
                .andExpect(jsonPath("$.items[0].inviteExpiresAt").doesNotExist());

        var response = mvc.perform(post(joinPath(home)).header("Authorization", auth(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.houseId").value(home.getId()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.pendingApproval").value(false)).andReturn().getResponse();
        assertThat(mapper.readTree(response.getContentAsString()).path("membershipId").asLong()).isPositive();
        assertThat(members.findByHouseIdAndUserId(home.getId(), viewer.getId())).get().extracting(HouseMember::isActive).isEqualTo(true);
        assertThat(pending.getStatus()).isEqualTo(HouseJoinRequestStatus.ACCEPTED);
        assertThat(home.getCurrentMemberCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from notification where user_id=? and type='HOUSE_MEMBER_JOINED'", Long.class, author.getId())).isEqualTo(1L);
        mvc.perform(get("/api/v1/houses/" + home.getId()).header("Authorization", auth(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.myRole").value("MEMBER"));
        mvc.perform(get(path()).header("Authorization", auth(viewer)))
                .andExpect(jsonPath("$.items[0].isMember").value(true))
                .andExpect(jsonPath("$.items[0].canJoin").value(false));
        mvc.perform(post(joinPath(home)).header("Authorization", auth(viewer)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HOUSE_ALREADY_MEMBER"));
        assertThat(home.getCurrentMemberCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 작성자가_일반_구성원인_집도_함께_선택할_수_있다(boolean autoJoin) throws Exception {
        var otherOwner = users.save(User.signUp());
        House shared = house(otherOwner, true, null);
        shared.changeOnboardingAutoJoinEnabled(autoJoin);
        addMember(shared, author);
        mvc.perform(get(path()).header("Authorization", auth(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].houseId").value(shared.getId()))
                .andExpect(jsonPath("$.items[1].houseId").value(home.getId()));
        mvc.perform(post(joinPath(shared)).header("Authorization", auth(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pendingApproval").value(!autoJoin));
        if (!autoJoin) {
            assertThat(jdbc.queryForObject("select count(*) from notification where user_id=? and type='HOUSE_JOIN_REQUEST_CREATED'", Long.class, otherOwner.getId())).isEqualTo(1L);
            assertThat(jdbc.queryForObject("select count(*) from notification where user_id=? and type='HOUSE_JOIN_REQUEST_CREATED'", Long.class, author.getId())).isZero();
            assertThat(members.findByHouseIdAndUserId(shared.getId(), viewer.getId())).isEmpty();
        }
    }

    @Test void 작성자가_떠났거나_강퇴된_집과_삭제된_집은_목록과_입주에서_제외한다() throws Exception {
        members.findByHouseIdAndUserId(home.getId(), author.getId()).orElseThrow().leave();
        var kicked = house(author, false, 4);
        members.findByHouseIdAndUserId(kicked.getId(), author.getId()).orElseThrow().kick();
        var deleted = house(author, false, 4);
        deleted.softDelete();
        mvc.perform(get(path()).header("Authorization", auth(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        for (House excluded : new House[]{home, kicked, deleted}) {
            mvc.perform(post(joinPath(excluded)).header("Authorization", auth(viewer)))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOUSE_NOT_FOUND"));
            assertThat(members.findByHouseIdAndUserId(excluded.getId(), viewer.getId())).isEmpty();
        }
    }

    @Test void 게시물과_관계없는_비공개_집의_ID로는_입주할_수_없다() throws Exception {
        House unrelated = house(users.save(User.signUp()), false, 4);
        mvc.perform(post(joinPath(unrelated)).header("Authorization", auth(viewer)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOUSE_NOT_FOUND"));
        assertThat(members.findByHouseIdAndUserId(unrelated.getId(), viewer.getId())).isEmpty();
    }

    @Test void 만석과_강퇴는_입주를_막고_알림이나_구성원_수를_바꾸지_않는다() throws Exception {
        home.updateSettings(null, null, null, 1, null);
        mvc.perform(get(path()).header("Authorization", auth(viewer)))
                .andExpect(jsonPath("$.items[0].isFull").value(true)).andExpect(jsonPath("$.items[0].canJoin").value(false));
        mvc.perform(post(joinPath(home)).header("Authorization", auth(viewer)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HOUSE_FULL"));
        var kicked = members.save(HouseMember.create(home, viewer, HouseMemberRole.MEMBER));
        kicked.kick();
        home.updateSettings(null, null, null, 4, null);
        mvc.perform(get(path()).header("Authorization", auth(viewer)))
                .andExpect(jsonPath("$.items[0].myMembershipStatus").value("KICKED"))
                .andExpect(jsonPath("$.items[0].canJoin").value(false));
        mvc.perform(post(joinPath(home)).header("Authorization", auth(viewer)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HOUSE_KICKED_MEMBER"));
        assertThat(home.getCurrentMemberCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notification where user_id=?", Long.class, author.getId())).isZero();
    }

    @Test void 탈퇴_이력은_기존_멤버십으로_재입주한다() throws Exception {
        home.changeOnboardingAutoJoinEnabled(true);
        var previous = members.save(HouseMember.create(home, viewer, HouseMemberRole.MEMBER));
        previous.leave();
        mvc.perform(post(joinPath(home)).header("Authorization", auth(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.membershipId").value(previous.getId()));
        assertThat(previous.isActive()).isTrue();
        assertThat(home.getCurrentMemberCount()).isEqualTo(2);
    }

    @Test void 동거_봇으로_찬_집은_봇이_양보해_입주할_수_있다() throws Exception {
        home.changeOnboardingAutoJoinEnabled(true);
        home.updateSettings(null, null, null, 2, null);
        var bot = users.save(User.bot(UUID.randomUUID().toString(), "이웃 봇", null));
        var botMember = addMember(home, bot);
        mvc.perform(get(path()).header("Authorization", auth(viewer)))
                .andExpect(jsonPath("$.items[0].currentMemberCount").value(1))
                .andExpect(jsonPath("$.items[0].isFull").value(false))
                .andExpect(jsonPath("$.items[0].canJoin").value(true));
        mvc.perform(post(joinPath(home)).header("Authorization", auth(viewer))).andExpect(status().isOk());
        assertThat(botMember.getStatus()).isEqualTo(HouseMemberStatus.LEFT);
        assertThat(home.getCurrentMemberCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void 공개_여부와_무관하게_바로입주_설정에_따라_신청과_즉시입주를_구분한다(boolean isPublic, boolean autoJoin) throws Exception {
        home.updateSettings(null, null, null, null, isPublic);
        home.changeOnboardingAutoJoinEnabled(autoJoin);
        mvc.perform(get(path()).header("Authorization", auth(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].requiresApproval").value(!autoJoin))
                .andExpect(jsonPath("$.items[0].canJoin").value(true));
        var response = mvc.perform(post(joinPath(home)).header("Authorization", auth(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pendingApproval").value(!autoJoin))
                .andReturn().getResponse();
        if (autoJoin) {
            assertThat(home.getCurrentMemberCount()).isEqualTo(2);
            assertThat(members.findByHouseIdAndUserId(home.getId(), viewer.getId())).isPresent();
            assertThat(requests.findByHouseIdAndUserId(home.getId(), viewer.getId())).isEmpty();
        } else {
            assertThat(home.getCurrentMemberCount()).isEqualTo(1);
            assertThat(members.findByHouseIdAndUserId(home.getId(), viewer.getId())).isEmpty();
            assertThat(requests.findByHouseIdAndUserId(home.getId(), viewer.getId())).get()
                    .extracting(HouseJoinRequest::getStatus).isEqualTo(HouseJoinRequestStatus.PENDING);
            assertThat(jdbc.queryForObject("select count(*) from notification where user_id=? and type='HOUSE_JOIN_REQUEST_CREATED'", Long.class, author.getId())).isEqualTo(1L);
            mvc.perform(get(path()).header("Authorization", auth(viewer)))
                    .andExpect(jsonPath("$.items[0].myJoinRequestStatus").value("PENDING"))
                    .andExpect(jsonPath("$.items[0].canJoin").value(false));
            mvc.perform(post(joinPath(home)).header("Authorization", auth(viewer)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HOUSE_JOIN_REQUEST_ALREADY_PENDING"));
            long requestId = mapper.readTree(response.getContentAsString()).path("joinRequestId").asLong();
            mvc.perform(post("/api/v1/houses/" + home.getId() + "/join-requests/" + requestId + "/accept").header("Authorization", auth(author)))
                    .andExpect(status().isOk());
            assertThat(home.getCurrentMemberCount()).isEqualTo(2);
        }
    }

    @Test void 삭제된_게시물은_조회와_입주가_모두_불가하다() throws Exception {
        feedPost.delete(Instant.now());
        hiddenPost();
    }

    @Test void 탈퇴한_작성자의_게시물은_조회와_입주가_모두_불가하다() throws Exception {
        author.softDelete(Instant.now());
        hiddenPost();
    }

    @Test void 차단한_작성자의_게시물은_조회와_입주가_모두_불가하다() throws Exception {
        blocks.save(new UserBlock(viewer.getId(), author.getId(), Instant.now()));
        hiddenPost();
    }

    private void hiddenPost() throws Exception {
        mvc.perform(get(path()).header("Authorization", auth(viewer)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FEED_POST_NOT_FOUND"));
        mvc.perform(post(joinPath(home)).header("Authorization", auth(viewer)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FEED_POST_NOT_FOUND"));
        assertThat(members.findByHouseIdAndUserId(home.getId(), viewer.getId())).isEmpty();
    }

    @Test void 인증과_활성_회원_검사를_거친다() throws Exception {
        mvc.perform(get(path())).andExpect(status().isUnauthorized());
        mvc.perform(post(joinPath(home))).andExpect(status().isUnauthorized());
        viewer.softDelete(Instant.now());
        mvc.perform(get(path()).header("Authorization", auth(viewer))).andExpect(status().isUnauthorized());
        mvc.perform(post(joinPath(home)).header("Authorization", auth(viewer))).andExpect(status().isUnauthorized());
        var bot = users.save(User.bot(UUID.randomUUID().toString(), "봇", null));
        mvc.perform(get(path()).header("Authorization", auth(bot))).andExpect(status().isUnauthorized());
        mvc.perform(post(joinPath(home)).header("Authorization", auth(bot))).andExpect(status().isUnauthorized());
    }
}
