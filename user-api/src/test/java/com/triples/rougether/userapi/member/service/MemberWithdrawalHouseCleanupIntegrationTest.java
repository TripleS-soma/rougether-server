package com.triples.rougether.userapi.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseJoinRequest;
import com.triples.rougether.domain.house.entity.HouseJoinRequestStatus;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.entity.HouseMission;
import com.triples.rougether.domain.house.entity.HouseMissionType;
import com.triples.rougether.domain.house.repository.HouseJoinRequestRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseMissionRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.invite.repository.InviteRewardRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.policy.SignupWalletPolicy;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.notification.repository.UserDeviceTokenRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Category;
import com.triples.rougether.domain.routine.entity.PrivacyScope;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseJoinService;
import com.triples.rougether.userapi.invite.error.InviteErrorCode;
import com.triples.rougether.userapi.invite.service.InviteService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

// 탈퇴 시 집·초대 도메인 정리(#239)를 실제 DB로 검증함 — 멤버십 LEFT·정원 감소·연동 해제·신청 철회·
// 소유권 승계·집 해체·승인/초대 가드. 커밋 경계(가드의 거절 커밋, 중간 실패 롤백)를 봐야 해서
// 테스트 트랜잭션 없이 실제 커밋으로 확인함.
@SpringBootTest
class MemberWithdrawalHouseCleanupIntegrationTest {

    // 롤백 유도용 - 탈퇴 트랜잭션의 마지막 연산(deleteAllByUserId)에서 실패시켜 전체 롤백을 검증함.
    @MockitoBean
    private UserDeviceTokenRepository userDeviceTokenRepository;

    @Autowired private MemberWithdrawalService memberWithdrawalService;
    @Autowired private HouseJoinService houseJoinService;
    @Autowired private InviteService inviteService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserWalletRepository walletRepository;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private HouseJoinRequestRepository houseJoinRequestRepository;
    @Autowired private HouseMissionRepository houseMissionRepository;
    @Autowired private RoutineRepository routineRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private InviteRewardRepository inviteRewardRepository;

    private User signUp(String prefix) {
        return userRepository.save(User.signUp(prefix + "-" + UUID.randomUUID() + "@rougether.dev"));
    }

    private House createHouseWithOwner(User owner) {
        String code = ("WD" + UUID.randomUUID().toString().replace("-", ""))
                .substring(0, 8).toUpperCase();
        House house = houseRepository.save(House.create(
                owner, "탈퇴정리 집", null, null, 6, code, Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        return house;
    }

    private HouseMember activeMembership(Long houseId, Long userId) {
        return houseMemberRepository.findByHouseIdAndUserId(houseId, userId).orElseThrow();
    }

    @Test
    void 탈퇴하면_멤버십_LEFT_정원_감소_연동_해제_pending_신청_철회까지_정리된다() {
        User owner = signUp("cleanup-owner");
        User withdrawing = signUp("cleanup-user");
        House joinedHouse = createHouseWithOwner(owner);
        houseJoinService.join(withdrawing.getId(), joinedHouse.getId());

        // 단체미션 연동 루틴 - 탈퇴자 것만 해제되고 남은 구성원 것은 유지돼야 함.
        Long missionId = houseMissionRepository.save(HouseMission.create(
                joinedHouse, "다같이 운동", HouseMissionType.WEEKLY_MEMBER_COUNT, 3, null, null)).getId();
        Routine linkedRoutine = Routine.create(withdrawing, null, "연동 루틴", AuthType.CHECK,
                null, null, null, null, null);
        linkedRoutine.linkHouseMission(missionId);
        linkedRoutine = routineRepository.save(linkedRoutine);
        Routine ownerRoutine = Routine.create(owner, null, "소유자 루틴", AuthType.CHECK,
                null, null, null, null, null);
        ownerRoutine.linkHouseMission(missionId);
        ownerRoutine = routineRepository.save(ownerRoutine);
        Category linkedCategory = Category.create(
                withdrawing, "집 연동", "#FFFFFF", null, 1, PrivacyScope.PRIVATE);
        linkedCategory.linkHouse(joinedHouse.getId());
        linkedCategory = categoryRepository.save(linkedCategory);

        // 다른 집에는 입주 신청만 대기 중.
        User otherOwner = signUp("cleanup-other-owner");
        House appliedHouse = createHouseWithOwner(otherOwner);
        Long requestId = houseJoinService.requestJoin(withdrawing.getId(), appliedHouse.getId())
                .requestId();

        memberWithdrawalService.withdraw(withdrawing.getId());

        HouseMember left = activeMembership(joinedHouse.getId(), withdrawing.getId());
        assertThat(left.getStatus()).isEqualTo(HouseMemberStatus.LEFT);
        assertThat(left.getLeftAt()).isNotNull();
        assertThat(houseRepository.findById(joinedHouse.getId()).orElseThrow()
                .getCurrentMemberCount()).isEqualTo(1);
        assertThat(activeMembership(joinedHouse.getId(), owner.getId()).getStatus())
                .isEqualTo(HouseMemberStatus.ACTIVE);
        // 강퇴·집 나가기와 동일한 부수 정리 - 미션·카테고리 연동 해제(탈퇴자 것만).
        assertThat(routineRepository.findById(linkedRoutine.getId()).orElseThrow()
                .getHouseMissionId()).isNull();
        assertThat(routineRepository.findById(ownerRoutine.getId()).orElseThrow()
                .getHouseMissionId()).isEqualTo(missionId);
        assertThat(categoryRepository.findById(linkedCategory.getId()).orElseThrow()
                .getHouseId()).isNull();
        // 대기 중 신청은 철회(REJECTED 재사용)되고 처리 시각이 남음.
        HouseJoinRequest rejected = houseJoinRequestRepository.findById(requestId).orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(HouseJoinRequestStatus.REJECTED);
        assertThat(rejected.getProcessedAt()).isNotNull();
        assertThat(houseRepository.findById(appliedHouse.getId()).orElseThrow()
                .getCurrentMemberCount()).isEqualTo(1);
    }

    @Test
    void 탈퇴_트랜잭션_중간_실패_시_집_정리와_soft_delete_가_전부_롤백된다() {
        User owner = signUp("rollback-owner");
        User withdrawing = signUp("rollback-user");
        House house = createHouseWithOwner(owner);
        houseJoinService.join(withdrawing.getId(), house.getId());
        User otherOwner = signUp("rollback-other-owner");
        House appliedHouse = createHouseWithOwner(otherOwner);
        Long requestId = houseJoinService.requestJoin(withdrawing.getId(), appliedHouse.getId())
                .requestId();
        doThrow(new RuntimeException("device token delete down"))
                .when(userDeviceTokenRepository).deleteAllByUserId(withdrawing.getId());

        assertThatThrownBy(() -> memberWithdrawalService.withdraw(withdrawing.getId()))
                .isInstanceOf(RuntimeException.class);

        // 트랜잭션 마지막 연산 실패 → 집 정리 포함 전부 되돌아가야 함(부분 커밋 금지).
        assertThat(userRepository.findById(withdrawing.getId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(activeMembership(house.getId(), withdrawing.getId()).getStatus())
                .isEqualTo(HouseMemberStatus.ACTIVE);
        assertThat(houseRepository.findById(house.getId()).orElseThrow().getCurrentMemberCount())
                .isEqualTo(2);
        assertThat(houseJoinRequestRepository.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(HouseJoinRequestStatus.PENDING);
    }

    @Test
    void 소유자가_탈퇴하면_가입일_최선임_멤버가_소유권을_승계한다() {
        User owner = signUp("succession-owner");
        House house = createHouseWithOwner(owner);
        User senior = signUp("succession-senior");
        User junior = signUp("succession-junior");
        houseJoinService.join(senior.getId(), house.getId());
        houseJoinService.join(junior.getId(), house.getId());
        // 가입 시각을 명시적으로 벌려 최선임 판정을 결정적으로 만듦.
        HouseMember seniorMember = activeMembership(house.getId(), senior.getId());
        HouseMember juniorMember = activeMembership(house.getId(), junior.getId());
        ReflectionTestUtils.setField(seniorMember, "joinedAt", Instant.now().minus(Duration.ofHours(2)));
        ReflectionTestUtils.setField(juniorMember, "joinedAt", Instant.now().minus(Duration.ofHours(1)));
        houseMemberRepository.save(seniorMember);
        houseMemberRepository.save(juniorMember);

        memberWithdrawalService.withdraw(owner.getId());

        HouseMember leftOwner = activeMembership(house.getId(), owner.getId());
        assertThat(leftOwner.getStatus()).isEqualTo(HouseMemberStatus.LEFT);
        assertThat(leftOwner.getRole()).isEqualTo(HouseMemberRole.MEMBER);
        HouseMember successor = activeMembership(house.getId(), senior.getId());
        assertThat(successor.getRole()).isEqualTo(HouseMemberRole.OWNER);
        assertThat(successor.getStatus()).isEqualTo(HouseMemberStatus.ACTIVE);
        House after = houseRepository.findById(house.getId()).orElseThrow();
        assertThat(after.getOwner().getId()).isEqualTo(senior.getId());
        assertThat(after.isDeleted()).isFalse();
        assertThat(after.getCurrentMemberCount()).isEqualTo(2);
    }

    @Test
    void 가입일이_같으면_membership_id_가_작은_멤버가_승계한다() {
        User owner = signUp("tie-owner");
        House house = createHouseWithOwner(owner);
        User first = signUp("tie-first");
        User second = signUp("tie-second");
        houseJoinService.join(first.getId(), house.getId());
        houseJoinService.join(second.getId(), house.getId());
        Instant sameJoinedAt = Instant.now().minus(Duration.ofHours(1));
        HouseMember firstMember = activeMembership(house.getId(), first.getId());
        HouseMember secondMember = activeMembership(house.getId(), second.getId());
        ReflectionTestUtils.setField(firstMember, "joinedAt", sameJoinedAt);
        ReflectionTestUtils.setField(secondMember, "joinedAt", sameJoinedAt);
        houseMemberRepository.save(firstMember);
        houseMemberRepository.save(secondMember);
        assertThat(firstMember.getId()).isLessThan(secondMember.getId());

        memberWithdrawalService.withdraw(owner.getId());

        assertThat(activeMembership(house.getId(), first.getId()).getRole())
                .isEqualTo(HouseMemberRole.OWNER);
        assertThat(activeMembership(house.getId(), second.getId()).getRole())
                .isEqualTo(HouseMemberRole.MEMBER);
        assertThat(houseRepository.findById(house.getId()).orElseThrow().getOwner().getId())
                .isEqualTo(first.getId());
    }

    @Test
    void 마지막_1인_소유자가_탈퇴하면_집이_해체되고_초대코드로_가입할_수_없다() {
        User owner = signUp("dissolve-owner");
        House house = createHouseWithOwner(owner);
        String inviteCode = house.getInviteCode();

        memberWithdrawalService.withdraw(owner.getId());

        House dissolved = houseRepository.findById(house.getId()).orElseThrow();
        assertThat(dissolved.isDeleted()).isTrue();
        assertThat(dissolved.getCurrentMemberCount()).isZero();
        // 해체된 집의 초대코드로는 신규 가입 불가.
        User newcomer = signUp("dissolve-newcomer");
        assertThatThrownBy(() -> houseJoinService.joinByCode(newcomer.getId(), inviteCode))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(HouseErrorCode.INVITE_CODE_INVALID);
    }

    @Test
    void 탈퇴자의_신청을_승인하면_거절_처리와_에러_응답이_함께_되고_정원은_불변이다() {
        User owner = signUp("guard-owner");
        House house = createHouseWithOwner(owner);
        User applicant = signUp("guard-applicant");
        memberWithdrawalService.withdraw(applicant.getId());
        // 탈퇴 트랜잭션의 신청 철회와 엇갈려 남은 잔여 PENDING 신청을 재현함.
        Long requestId = houseJoinRequestRepository.save(HouseJoinRequest.create(
                house, userRepository.findById(applicant.getId()).orElseThrow())).getId();

        assertThatThrownBy(() -> houseJoinService.acceptRequest(owner.getId(), house.getId(), requestId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(HouseErrorCode.HOUSE_JOIN_REQUEST_APPLICANT_WITHDRAWN);

        // 거절 전환은 에러와 함께 커밋됨(noRollbackFor) - 잔여 신청이 목록에 영원히 남지 않음.
        assertThat(houseJoinRequestRepository.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(HouseJoinRequestStatus.REJECTED);
        assertThat(houseMemberRepository.findByHouseIdAndUserId(house.getId(), applicant.getId()))
                .isEmpty();
        assertThat(houseRepository.findById(house.getId()).orElseThrow().getCurrentMemberCount())
                .isEqualTo(1);
    }

    @Test
    void 탈퇴한_초대자의_코드는_무효이고_양쪽_지갑_잔액이_변하지_않는다() {
        User inviter = signUp("invite-withdrawn-inviter");
        User invitee = signUp("invite-withdrawn-invitee");
        walletRepository.saveAll(SignupWalletPolicy.issueAll(inviter));
        walletRepository.saveAll(SignupWalletPolicy.issueAll(invitee));
        String code = inviteService.getMyCode(inviter.getId()).code();
        int inviterBalanceBefore = coinBalance(inviter.getId());
        int inviteeBalanceBefore = coinBalance(invitee.getId());

        memberWithdrawalService.withdraw(inviter.getId());

        assertThatThrownBy(() -> inviteService.redeem(invitee.getId(), code))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(InviteErrorCode.INVITE_CODE_NOT_FOUND);

        // 재화 정합성 - 탈퇴 계정 지갑으로도, 사용자 지갑으로도 지급이 없어야 함.
        assertThat(coinBalance(inviter.getId())).isEqualTo(inviterBalanceBefore);
        assertThat(coinBalance(invitee.getId())).isEqualTo(inviteeBalanceBefore);
        assertThat(inviteRewardRepository
                .countByInviterIdAndInviterRewardAmountGreaterThan(inviter.getId(), 0)).isZero();
    }

    private int coinBalance(Long userId) {
        return walletRepository.findByUserIdAndCurrencyType(userId, CurrencyType.COIN)
                .map(UserWallet::getBalance)
                .orElse(0);
    }

    @Test
    void 탈퇴_후_잔여_토큰으로는_초대코드로_재가입할_수_없다() {
        // 탈퇴 후 access token 만료 전(최대 30분) 재가입으로 멤버십 정리(LEFT·정원 감소)가 되돌아가지 않아야 함.
        User owner = signUp("rejoin-owner");
        User withdrawing = signUp("rejoin-user");
        House house = createHouseWithOwner(owner);
        houseJoinService.join(withdrawing.getId(), house.getId());
        memberWithdrawalService.withdraw(withdrawing.getId());

        assertThatThrownBy(() -> houseJoinService.joinByCode(withdrawing.getId(), house.getInviteCode()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.INVALID_TOKEN);

        assertThat(activeMembership(house.getId(), withdrawing.getId()).getStatus())
                .isEqualTo(HouseMemberStatus.LEFT);
        assertThat(houseRepository.findById(house.getId()).orElseThrow().getCurrentMemberCount())
                .isEqualTo(1);
    }

    @Test
    void 탈퇴한_계정은_잔여_토큰으로_초대코드를_사용할_수_없고_지갑이_변하지_않는다() {
        User inviter = signUp("invitee-withdrawn-inviter");
        User invitee = signUp("invitee-withdrawn-invitee");
        walletRepository.saveAll(SignupWalletPolicy.issueAll(inviter));
        walletRepository.saveAll(SignupWalletPolicy.issueAll(invitee));
        String code = inviteService.getMyCode(inviter.getId()).code();
        int inviterBalanceBefore = coinBalance(inviter.getId());
        int inviteeBalanceBefore = coinBalance(invitee.getId());

        memberWithdrawalService.withdraw(invitee.getId());

        assertThatThrownBy(() -> inviteService.redeem(invitee.getId(), code))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.INVALID_TOKEN);

        // 탈퇴 계정 지갑 적립도, 초대자 보상 한도 소진도 없어야 함.
        assertThat(coinBalance(inviter.getId())).isEqualTo(inviterBalanceBefore);
        assertThat(coinBalance(invitee.getId())).isEqualTo(inviteeBalanceBefore);
        assertThat(inviteRewardRepository
                .countByInviterIdAndInviterRewardAmountGreaterThan(inviter.getId(), 0)).isZero();
    }
}
