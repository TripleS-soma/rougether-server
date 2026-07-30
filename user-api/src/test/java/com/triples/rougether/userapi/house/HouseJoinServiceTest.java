package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseJoinRequest;
import com.triples.rougether.domain.house.entity.HouseJoinRequestStatus;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseJoinRequestRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.auth.error.AuthErrorCode;
import com.triples.rougether.userapi.house.dto.HouseJoinDetailResponse;
import com.triples.rougether.userapi.house.dto.HouseJoinRequestResponse;
import com.triples.rougether.userapi.house.dto.HouseJoinResponse;
import com.triples.rougether.userapi.house.dto.HousePreviewResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseJoinService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HouseJoinServiceTest {

    private static final String CODE = "ABCD2345";

    @Mock private HouseRepository houseRepository;
    @Mock private HouseMemberRepository houseMemberRepository;
    @Mock private HouseJoinRequestRepository houseJoinRequestRepository;
    @Mock private UserRepository userRepository;
    @InjectMocks private HouseJoinService houseJoinService;

    private House joinableHouse(Long id) {
        House house = mock(House.class);
        lenient().when(house.getId()).thenReturn(id);
        when(house.isDeleted()).thenReturn(false);
        lenient().when(house.isInviteExpired()).thenReturn(false);
        lenient().when(house.isFull()).thenReturn(false);
        return house;
    }

    // 참여 판정의 탈퇴 계정 가드가 락 조회하는 살아있는 사용자 스텁.
    private User liveJoiner(Long userId) {
        User joiner = mock(User.class);
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(joiner));
        return joiner;
    }

    @Test
    void 초대코드로_참여하면_MEMBER_ACTIVE로_등록되고_구성원_수가_증가한다() {
        House house = joinableHouse(1L);
        when(houseRepository.findWithLockByInviteCode(CODE)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.empty());
        liveJoiner(7L);
        when(houseMemberRepository.save(any(HouseMember.class))).thenAnswer(inv -> inv.getArgument(0));

        HouseJoinResponse response = houseJoinService.joinByCode(7L, CODE);

        verify(house).increaseMemberCount();
        verify(houseMemberRepository).save(any(HouseMember.class));
        assertThat(response.houseId()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo(HouseMemberStatus.ACTIVE);
    }

    @Test
    void 탈퇴한_계정의_참여는_인증_무효로_차단된다() {
        House house = joinableHouse(1L);
        when(houseRepository.findWithLockByInviteCode(CODE)).thenReturn(Optional.of(house));
        User withdrawn = mock(User.class);
        when(withdrawn.isDeleted()).thenReturn(true);
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(withdrawn));

        assertThatThrownBy(() -> houseJoinService.joinByCode(7L, CODE))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(AuthErrorCode.INVALID_TOKEN));
        verify(houseMemberRepository, never()).save(any());
        verify(house, never()).increaseMemberCount();
    }

    @Test
    void 없는_초대코드면_404() {
        when(houseRepository.findWithLockByInviteCode(CODE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseJoinService.joinByCode(7L, CODE))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.INVITE_CODE_INVALID));
    }

    @Test
    void 삭제된_집의_초대코드는_404() {
        House house = mock(House.class);
        when(house.isDeleted()).thenReturn(true);
        when(houseRepository.findWithLockByInviteCode(CODE)).thenReturn(Optional.of(house));

        assertThatThrownBy(() -> houseJoinService.joinByCode(7L, CODE))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.INVITE_CODE_INVALID));
    }

    @Test
    void 만료된_초대코드는_거부한다() {
        House house = mock(House.class);
        when(house.isDeleted()).thenReturn(false);
        when(house.isInviteExpired()).thenReturn(true);
        when(houseRepository.findWithLockByInviteCode(CODE)).thenReturn(Optional.of(house));

        assertThatThrownBy(() -> houseJoinService.joinByCode(7L, CODE))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.INVITE_CODE_EXPIRED));
        verify(houseMemberRepository, never()).save(any());
        verify(house, never()).increaseMemberCount();
    }

    @Test
    void 정원이_가득_차면_거부한다() {
        House house = mock(House.class);
        when(house.getId()).thenReturn(1L);
        when(house.isDeleted()).thenReturn(false);
        when(house.isInviteExpired()).thenReturn(false);
        when(house.isFull()).thenReturn(true);
        when(houseRepository.findWithLockByInviteCode(CODE)).thenReturn(Optional.of(house));
        liveJoiner(7L);
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseJoinService.joinByCode(7L, CODE))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_FULL));
        verify(houseMemberRepository, never()).save(any());
        verify(house, never()).increaseMemberCount();
    }

    @Test
    void 이미_참여_중이면_거부한다() {
        // ALREADY_MEMBER 는 정원 검사 전에 판정되므로 isFull stub 은 두지 않는다(strict stubs).
        House house = mock(House.class);
        when(house.getId()).thenReturn(1L);
        when(house.isDeleted()).thenReturn(false);
        when(house.isInviteExpired()).thenReturn(false);
        when(houseRepository.findWithLockByInviteCode(CODE)).thenReturn(Optional.of(house));
        liveJoiner(7L);
        HouseMember active = mock(HouseMember.class);
        when(active.isActive()).thenReturn(true);
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> houseJoinService.joinByCode(7L, CODE))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_ALREADY_MEMBER));
        verify(houseMemberRepository, never()).save(any());
        verify(house, never()).increaseMemberCount();
    }

    @Test
    void LEFT_이력이_있으면_새_row_대신_재활성화한다() {
        House house = joinableHouse(1L);
        when(houseRepository.findWithLockByInviteCode(CODE)).thenReturn(Optional.of(house));
        liveJoiner(7L);
        HouseMember left = mock(HouseMember.class);
        when(left.getId()).thenReturn(12L);
        when(left.isActive()).thenReturn(false);
        when(left.getStatus()).thenReturn(HouseMemberStatus.ACTIVE); // reactivate 후 응답용
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(left));

        HouseJoinResponse response = houseJoinService.joinByCode(7L, CODE);

        verify(left).reactivate();
        verify(houseMemberRepository, never()).save(any());
        verify(house).increaseMemberCount();
        assertThat(response.membershipId()).isEqualTo(12L);
    }

    @Test
    void houseId로_참여하면_MEMBER_ACTIVE로_등록되고_구성원_수가_증가한다() {
        House house = mock(House.class);
        when(house.getId()).thenReturn(1L);
        when(house.isDeleted()).thenReturn(false);
        when(house.isFull()).thenReturn(false);
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.empty());
        liveJoiner(7L);
        when(houseMemberRepository.save(any(HouseMember.class))).thenAnswer(inv -> inv.getArgument(0));

        HouseJoinDetailResponse response = houseJoinService.join(7L, 1L);

        verify(house).increaseMemberCount();
        assertThat(response.houseId()).isEqualTo(1L);
        assertThat(response.userId()).isEqualTo(7L);
        assertThat(response.role()).isEqualTo(HouseMemberRole.MEMBER);
        assertThat(response.status()).isEqualTo(HouseMemberStatus.ACTIVE);
        assertThat(response.joinedAt()).isNotNull();
    }

    @Test
    void 없는_houseId면_404() {
        when(houseRepository.findWithLockById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseJoinService.join(7L, 99L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_FOUND));
    }

    @Test
    void 삭제된_집에_houseId로_참여하면_404() {
        House house = mock(House.class);
        when(house.isDeleted()).thenReturn(true);
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));

        assertThatThrownBy(() -> houseJoinService.join(7L, 1L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_FOUND));
    }

    @Test
    void houseId_참여도_정원이_가득_차면_거부한다() {
        House house = mock(House.class);
        when(house.getId()).thenReturn(1L);
        when(house.isDeleted()).thenReturn(false);
        when(house.isFull()).thenReturn(true);
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));
        liveJoiner(7L);
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseJoinService.join(7L, 1L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_FULL));
        verify(house, never()).increaseMemberCount();
    }

    @Test
    void houseId로_재가입하면_기존_row를_재활성화한다() {
        House house = mock(House.class);
        when(house.getId()).thenReturn(1L);
        when(house.isDeleted()).thenReturn(false);
        when(house.isFull()).thenReturn(false);
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));
        liveJoiner(7L);
        HouseMember left = mock(HouseMember.class);
        when(left.getId()).thenReturn(12L);
        when(left.isActive()).thenReturn(false);
        when(left.getStatus()).thenReturn(HouseMemberStatus.ACTIVE);
        when(left.getRole()).thenReturn(HouseMemberRole.MEMBER);
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(left));

        HouseJoinDetailResponse response = houseJoinService.join(7L, 1L);

        verify(left).reactivate();
        verify(houseMemberRepository, never()).save(any());
        verify(house).increaseMemberCount();
        assertThat(response.membershipId()).isEqualTo(12L);
    }

    @Test
    void houseId_참여도_이미_참여_중이면_거부한다() {
        House house = mock(House.class);
        when(house.getId()).thenReturn(1L);
        when(house.isDeleted()).thenReturn(false);
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));
        liveJoiner(7L);
        HouseMember active = mock(HouseMember.class);
        when(active.isActive()).thenReturn(true);
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> houseJoinService.join(7L, 1L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_ALREADY_MEMBER));
        verify(houseMemberRepository, never()).save(any());
    }

    @Test
    void 탐색_입주_신청은_PENDING만_만들고_구성원_수는_늘리지_않는다() {
        House house = joinableHouse(1L);
        User applicant = mock(User.class);
        when(applicant.getId()).thenReturn(7L);
        when(applicant.getNickname()).thenReturn("루티니");
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.empty());
        when(houseJoinRequestRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.empty());
        when(userRepository.getReferenceById(7L)).thenReturn(applicant);
        when(houseJoinRequestRepository.save(any(HouseJoinRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        HouseJoinRequestResponse response = houseJoinService.requestJoin(7L, 1L);

        assertThat(response.status()).isEqualTo(HouseJoinRequestStatus.PENDING);
        assertThat(response.nickname()).isEqualTo("루티니");
        verify(houseJoinRequestRepository).save(any(HouseJoinRequest.class));
        verify(house, never()).increaseMemberCount();
        verify(houseMemberRepository, never()).save(any());
    }

    @Test
    void 이미_PENDING이면_그사이_정원이_차도_중복_신청_오류를_우선한다() {
        House house = House.create(
                mock(User.class), "가득 찬 집", null, null, 1, CODE, null);
        User applicant = mock(User.class);
        HouseJoinRequest request = HouseJoinRequest.create(house, applicant);
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.empty());
        when(houseJoinRequestRepository.findByHouseIdAndUserId(1L, 7L))
                .thenReturn(Optional.of(request));

        assertThat(house.isFull()).isTrue();
        assertThatThrownBy(() -> houseJoinService.requestJoin(7L, 1L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_JOIN_REQUEST_ALREADY_PENDING));
    }

    @Test
    void 방장이_입주_신청을_수락하면_구성원_ACTIVE와_인원_증가를_함께_처리한다() {
        House house = joinableHouse(1L);
        User owner = mock(User.class);
        User applicant = mock(User.class);
        when(owner.getId()).thenReturn(3L);
        when(applicant.getId()).thenReturn(7L);
        when(house.getOwner()).thenReturn(owner);
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));
        HouseJoinRequest request = HouseJoinRequest.create(house, applicant);
        when(houseJoinRequestRepository.findWithLockByIdAndHouseId(21L, 1L))
                .thenReturn(Optional.of(request));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.empty());
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(applicant));
        when(houseMemberRepository.save(any(HouseMember.class))).thenAnswer(inv -> inv.getArgument(0));

        HouseJoinDetailResponse response = houseJoinService.acceptRequest(3L, 1L, 21L);

        assertThat(response.status()).isEqualTo(HouseMemberStatus.ACTIVE);
        assertThat(request.getStatus()).isEqualTo(HouseJoinRequestStatus.ACCEPTED);
        verify(house).increaseMemberCount();
    }

    @Test
    void 방장이_입주_신청을_거절하면_구성원_수는_바뀌지_않는다() {
        House house = joinableHouse(1L);
        User owner = mock(User.class);
        User applicant = mock(User.class);
        when(owner.getId()).thenReturn(3L);
        when(house.getOwner()).thenReturn(owner);
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));
        HouseJoinRequest request = HouseJoinRequest.create(house, applicant);
        when(houseJoinRequestRepository.findWithLockByIdAndHouseId(21L, 1L))
                .thenReturn(Optional.of(request));

        houseJoinService.rejectRequest(3L, 1L, 21L);

        assertThat(request.getStatus()).isEqualTo(HouseJoinRequestStatus.REJECTED);
        verify(house, never()).increaseMemberCount();
        verify(houseMemberRepository, never()).save(any());
    }

    @Test
    void 초대코드로_즉시가입하면_대기_신청도_ACCEPTED로_종결한다() {
        House house = joinableHouse(1L);
        User applicant = mock(User.class);
        when(houseRepository.findWithLockByInviteCode(CODE)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.empty());
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(applicant));
        when(houseMemberRepository.save(any(HouseMember.class))).thenAnswer(inv -> inv.getArgument(0));
        HouseJoinRequest request = HouseJoinRequest.create(house, applicant);
        when(houseJoinRequestRepository.findByHouseIdAndUserId(1L, 7L))
                .thenReturn(Optional.of(request));

        houseJoinService.joinByCode(7L, CODE);

        assertThat(request.getStatus()).isEqualTo(HouseJoinRequestStatus.ACCEPTED);
    }

    @Test
    void 미리보기는_만료된_코드도_만료_표시와_함께_보여준다() {
        House house = mock(House.class);
        when(house.getId()).thenReturn(1L);
        when(house.getName()).thenReturn("아침 루틴 하우스");
        when(house.getCurrentMemberCount()).thenReturn(3);
        when(house.getMaxMembers()).thenReturn(4);
        when(house.isDeleted()).thenReturn(false);
        when(house.isInviteExpired()).thenReturn(true);
        when(houseRepository.findByInviteCode(CODE)).thenReturn(Optional.of(house));

        HousePreviewResponse response = houseJoinService.preview(CODE);

        assertThat(response.inviteExpired()).isTrue();
        assertThat(response.name()).isEqualTo("아침 루틴 하우스");
        assertThat(response.currentMemberCount()).isEqualTo(3);
    }

    @Test
    void 미리보기도_없는_코드면_404() {
        when(houseRepository.findByInviteCode(CODE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseJoinService.preview(CODE))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.INVITE_CODE_INVALID));
    }
}
