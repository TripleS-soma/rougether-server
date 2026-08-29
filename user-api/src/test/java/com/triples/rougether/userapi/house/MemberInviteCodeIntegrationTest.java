package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseJoinRequestStatus;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseJoinRequestRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseCommandService;
import com.triples.rougether.userapi.house.service.HouseJoinService;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// 구성원 개인 초대코드 전체 흐름 - 발급 → 미리보기(승인 필요 표시) → 참여(PENDING) → 방장 수락(입주 확정).
@SpringBootTest
@Transactional
class MemberInviteCodeIntegrationTest {

    @Autowired private HouseCommandService houseCommandService;
    @Autowired private HouseJoinService houseJoinService;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private HouseJoinRequestRepository houseJoinRequestRepository;
    @Autowired private UserRepository userRepository;

    private User owner;
    private User member;
    private User newcomer;
    private House house;
    private HouseMember membership;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.signUp("member-invite-owner@rougether.dev"));
        member = userRepository.save(User.signUp("member-invite-member@rougether.dev"));
        newcomer = userRepository.save(User.signUp("member-invite-newcomer@rougether.dev"));
        house = houseRepository.save(House.create(
                owner, "구성원 초대 집", null, null, 4, "MBRINV22",
                Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        membership = houseMemberRepository.save(
                HouseMember.create(house, member, HouseMemberRole.MEMBER));
        house.increaseMemberCount();
    }

    @Test
    void 구성원_코드_발급부터_방장_수락까지_전체_흐름이_이어진다() {
        var issued = houseCommandService.reissueInviteCode(member.getId(), house.getId());
        assertThat(issued.inviteCode()).isNotEqualTo(house.getInviteCode());
        assertThat(membership.getInviteCode()).isEqualTo(issued.inviteCode());

        var preview = houseJoinService.preview(issued.inviteCode());
        assertThat(preview.houseId()).isEqualTo(house.getId());
        assertThat(preview.requiresApproval()).isTrue();
        assertThat(preview.inviteExpired()).isFalse();

        var joinResponse = houseJoinService.joinByCode(newcomer.getId(), issued.inviteCode());
        assertThat(joinResponse.pendingApproval()).isTrue();
        assertThat(joinResponse.membershipId()).isNull();
        assertThat(house.getCurrentMemberCount()).isEqualTo(2);
        assertThat(houseMemberRepository.findByHouseIdAndUserId(house.getId(), newcomer.getId()))
                .isEmpty();
        assertThat(houseJoinRequestRepository.findById(joinResponse.joinRequestId())
                .orElseThrow().getStatus()).isEqualTo(HouseJoinRequestStatus.PENDING);

        var joined = houseJoinService.acceptRequest(
                owner.getId(), house.getId(), joinResponse.joinRequestId());
        assertThat(joined.status().name()).isEqualTo("ACTIVE");
        assertThat(house.getCurrentMemberCount()).isEqualTo(3);
        assertThat(houseJoinRequestRepository.findById(joinResponse.joinRequestId())
                .orElseThrow().getStatus()).isEqualTo(HouseJoinRequestStatus.ACCEPTED);
    }

    @Test
    void 소문자_공백이_섞인_구성원_코드_입력도_정규화해_미리보기와_신청이_동작한다() {
        // 링크·수기 입력은 소문자나 공백을 달고 오기 쉽다 - 발급 표기(영대문자)로 정규화해 인식해야 한다.
        var issued = houseCommandService.reissueInviteCode(member.getId(), house.getId());
        String sloppyInput = "  " + issued.inviteCode().toLowerCase(Locale.ROOT) + " ";

        var preview = houseJoinService.preview(sloppyInput);
        assertThat(preview.houseId()).isEqualTo(house.getId());
        assertThat(preview.requiresApproval()).isTrue();

        var joinResponse = houseJoinService.joinByCode(newcomer.getId(), sloppyInput);
        assertThat(joinResponse.pendingApproval()).isTrue();
        assertThat(houseJoinRequestRepository.findById(joinResponse.joinRequestId())
                .orElseThrow().getStatus()).isEqualTo(HouseJoinRequestStatus.PENDING);
    }

    @Test
    void 소문자로_입력한_집_공용_코드도_즉시가입된다() {
        var joinResponse = houseJoinService.joinByCode(
                newcomer.getId(), " " + house.getInviteCode().toLowerCase(Locale.ROOT) + " ");

        assertThat(joinResponse.pendingApproval()).isFalse();
        assertThat(joinResponse.membershipId()).isNotNull();
        assertThat(houseMemberRepository.findByHouseIdAndUserId(house.getId(), newcomer.getId()))
                .isPresent();
    }

    @Test
    void 구성원_코드_재발급은_본인_코드만_갈아끼우고_집_공용_코드는_유지한다() {
        String houseCode = house.getInviteCode();
        var first = houseCommandService.reissueInviteCode(member.getId(), house.getId());
        var second = houseCommandService.reissueInviteCode(member.getId(), house.getId());

        assertThat(house.getInviteCode()).isEqualTo(houseCode);
        assertThat(second.inviteCode()).isNotEqualTo(first.inviteCode());
        assertThatThrownBy(() -> houseJoinService.preview(first.inviteCode()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.INVITE_CODE_INVALID));
    }

    @Test
    void 초대자가_탈퇴하면_개인_코드는_즉시_무효가_된다() {
        var issued = houseCommandService.reissueInviteCode(member.getId(), house.getId());
        membership.leave();

        assertThat(membership.getInviteCode()).isNull();
        assertThatThrownBy(() -> houseJoinService.joinByCode(newcomer.getId(), issued.inviteCode()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.INVITE_CODE_INVALID));
        assertThatThrownBy(() -> houseJoinService.preview(issued.inviteCode()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.INVITE_CODE_INVALID));
    }

    @Test
    void 탈퇴_후_재가입해도_이전_개인_코드는_되살아나지_않는다() {
        var issued = houseCommandService.reissueInviteCode(member.getId(), house.getId());
        membership.leave();
        house.decreaseMemberCount();

        var rejoined = houseJoinService.joinByCode(member.getId(), "MBRINV22");
        assertThat(rejoined.membershipId()).isEqualTo(membership.getId());
        assertThat(membership.isActive()).isTrue();

        assertThat(membership.getInviteCode()).isNull();
        assertThatThrownBy(() -> houseJoinService.joinByCode(newcomer.getId(), issued.inviteCode()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.INVITE_CODE_INVALID));
    }

    @Test
    void 집_공용_코드_참여는_기존과_같이_즉시가입한다() {
        var joinResponse = houseJoinService.joinByCode(newcomer.getId(), "MBRINV22");

        assertThat(joinResponse.pendingApproval()).isFalse();
        assertThat(joinResponse.status().name()).isEqualTo("ACTIVE");
        assertThat(house.getCurrentMemberCount()).isEqualTo(3);
    }
}
