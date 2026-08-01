package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.goal.entity.Goal;
import com.triples.rougether.domain.goal.repository.GoalRepository;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseGoal;
import com.triples.rougether.domain.house.entity.HouseJoinRequest;
import com.triples.rougether.domain.house.entity.HouseJoinRequestStatus;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseGoalRepository;
import com.triples.rougether.domain.house.repository.HouseJoinRequestRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.dto.HouseJoinResponse;
import com.triples.rougether.userapi.house.dto.MyJoinRequestListResponse;
import com.triples.rougether.userapi.house.dto.MyJoinRequestListResponse.MyJoinRequestSummary;
import com.triples.rougether.userapi.house.service.HouseCommandService;
import com.triples.rougether.userapi.house.service.HouseJoinService;
import com.triples.rougether.userapi.house.service.HouseQueryService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// 내가 보낸 입주 신청 목록 쿼리 의미 검증 - 기본 PENDING·status 필터·탐색+개인 코드 신청 동시 포함·
// 타인 신청 미포함·삭제 집 제외·goals 요약 매핑을 실제 DB(H2)로 확인.
@SpringBootTest
@Transactional
class MyJoinRequestsQueryTest {

    @Autowired private HouseQueryService houseQueryService;
    @Autowired private HouseJoinService houseJoinService;
    @Autowired private HouseCommandService houseCommandService;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private HouseGoalRepository houseGoalRepository;
    @Autowired private HouseJoinRequestRepository houseJoinRequestRepository;
    @Autowired private GoalRepository goalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User me;
    private User owner;
    private House exploreHouse;
    private House rejectedHouse;
    private House deletedHouse;
    private HouseJoinRequest exploreRequest;

    @BeforeEach
    void setUp() {
        me = userRepository.save(User.signUp("my-join-requests-me@rougether.dev"));
        owner = userRepository.save(User.signUp("my-join-requests-owner@rougether.dev"));
        Instant expires = Instant.now().plus(Duration.ofDays(7));

        jdbcTemplate.update(
                "INSERT INTO goals (code, name, sort_order, is_active) VALUES ('morning_routine', '아침 루틴', 1, true)");
        Goal morningGoal = goalRepository.findByIdInAndActiveIsTrue(
                jdbcTemplate.queryForList("SELECT id FROM goals WHERE code = 'morning_routine'", Long.class)).get(0);

        // 탐색에서 신청한 집(PENDING, goal 1개)
        exploreHouse = houseRepository.save(
                House.create(owner, "탐색 신청 집", null, "house/cover-a.png", 4, "MYJRQ222", expires));
        houseMemberRepository.save(HouseMember.create(exploreHouse, owner, HouseMemberRole.OWNER));
        houseGoalRepository.save(HouseGoal.create(exploreHouse, morningGoal));
        exploreRequest = houseJoinRequestRepository.save(HouseJoinRequest.create(exploreHouse, me));

        // 거절된 신청이 있는 집
        rejectedHouse = houseRepository.save(
                House.create(owner, "거절된 집", null, null, 4, "MYJRQ333", expires));
        houseMemberRepository.save(HouseMember.create(rejectedHouse, owner, HouseMemberRole.OWNER));
        HouseJoinRequest rejected = houseJoinRequestRepository.save(HouseJoinRequest.create(rejectedHouse, me));
        rejected.reject();

        // 삭제된 집의 PENDING 신청(목록에서 제외돼야 함)
        deletedHouse = houseRepository.save(
                House.create(owner, "삭제된 집", null, null, 4, "MYJRQ444", expires));
        houseMemberRepository.save(HouseMember.create(deletedHouse, owner, HouseMemberRole.OWNER));
        houseJoinRequestRepository.save(HouseJoinRequest.create(deletedHouse, me));
        jdbcTemplate.update("UPDATE house SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deletedHouse.getId());

        // 타인의 PENDING 신청(내 목록에 나오면 안 됨)
        User other = userRepository.save(User.signUp("my-join-requests-other@rougether.dev"));
        houseJoinRequestRepository.save(HouseJoinRequest.create(exploreHouse, other));
    }

    @Test
    void 기본_PENDING_신청만_최신순으로_내려준다() {
        // 개인 초대코드 신청(pendingApproval)을 나중에 보내 최신순 정렬도 함께 검증
        House codeHouse = houseRepository.save(House.create(
                owner, "개인 코드 집", null, null, 4, "MYJRQ555",
                Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(codeHouse, owner, HouseMemberRole.OWNER));
        User inviter = userRepository.save(User.signUp("my-join-requests-inviter@rougether.dev"));
        houseMemberRepository.save(HouseMember.create(codeHouse, inviter, HouseMemberRole.MEMBER));
        codeHouse.increaseMemberCount();
        String memberCode = houseCommandService.reissueInviteCode(inviter.getId(), codeHouse.getId()).inviteCode();
        HouseJoinResponse joined = houseJoinService.joinByCode(me.getId(), memberCode);
        assertThat(joined.pendingApproval()).isTrue();
        jdbcTemplate.update("UPDATE house_join_requests SET requested_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().plusSeconds(60)), joined.joinRequestId());

        MyJoinRequestListResponse response = houseQueryService.getMyJoinRequests(
                me.getId(), HouseJoinRequestStatus.PENDING);

        // 최신 신청(개인 코드) 먼저. 거절·삭제 집·타인 신청은 제외
        assertThat(response.items()).extracting(MyJoinRequestSummary::requestId)
                .containsExactly(joined.joinRequestId(), exploreRequest.getId());
        assertThat(response.items()).extracting(MyJoinRequestSummary::status)
                .containsOnly(HouseJoinRequestStatus.PENDING);
    }

    @Test
    void status_필터로_거절된_신청을_조회한다() {
        MyJoinRequestListResponse response = houseQueryService.getMyJoinRequests(
                me.getId(), HouseJoinRequestStatus.REJECTED);

        assertThat(response.items()).extracting(MyJoinRequestSummary::houseId)
                .containsExactly(rejectedHouse.getId());
        assertThat(response.items().get(0).status()).isEqualTo(HouseJoinRequestStatus.REJECTED);
    }

    @Test
    void 카드_표시용_집_요약과_goals가_매핑된다() {
        MyJoinRequestListResponse response = houseQueryService.getMyJoinRequests(
                me.getId(), HouseJoinRequestStatus.PENDING);

        MyJoinRequestSummary summary = response.items().get(0);
        assertThat(summary.requestId()).isEqualTo(exploreRequest.getId());
        assertThat(summary.houseId()).isEqualTo(exploreHouse.getId());
        assertThat(summary.houseName()).isEqualTo("탐색 신청 집");
        assertThat(summary.coverImageKey()).isEqualTo("house/cover-a.png");
        assertThat(summary.requestedAt()).isNotNull();
        assertThat(summary.goals()).hasSize(1);
        assertThat(summary.goals().get(0).code()).isEqualTo("morning_routine");
        assertThat(summary.goals().get(0).name()).isEqualTo("아침 루틴");
    }

    @Test
    void 신청_이력이_없으면_빈_목록이다() {
        User nobody = userRepository.save(User.signUp("my-join-requests-nobody@rougether.dev"));

        assertThat(houseQueryService.getMyJoinRequests(nobody.getId(), HouseJoinRequestStatus.PENDING)
                .items()).isEmpty();
    }
}
