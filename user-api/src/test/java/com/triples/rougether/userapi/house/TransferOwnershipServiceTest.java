package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.userapi.house.dto.TransferOwnershipResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseMemberCommandService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TransferOwnershipServiceTest {

    @Mock private HouseRepository houseRepository;
    @Mock private HouseMemberRepository houseMemberRepository;
    @InjectMocks private HouseMemberCommandService houseMemberCommandService;

    private House aliveHouse() {
        House house = mock(House.class);
        when(house.isDeleted()).thenReturn(false);
        return house;
    }

    private HouseMember ownerRequester(Long membershipId) {
        HouseMember requester = mock(HouseMember.class);
        when(requester.isActive()).thenReturn(true);
        when(requester.isOwner()).thenReturn(true);
        // 대상 검증 전에 실패하는 케이스에선 안 쓰이므로 lenient.
        lenient().when(requester.getId()).thenReturn(membershipId);
        return requester;
    }

    private HouseMember activeTarget(Long membershipId, Long houseId, Long targetUserId) {
        House targetHouse = mock(House.class);
        when(targetHouse.getId()).thenReturn(houseId);
        User targetUser = mock(User.class);
        lenient().when(targetUser.getId()).thenReturn(targetUserId);
        HouseMember target = mock(HouseMember.class);
        when(target.isActive()).thenReturn(true);
        when(target.getHouse()).thenReturn(targetHouse);
        // 검증 단계에서 실패하는 케이스에선 안 쓰이므로 lenient.
        lenient().when(target.getUser()).thenReturn(targetUser);
        lenient().when(target.getId()).thenReturn(membershipId);
        return target;
    }

    @Test
    void 소유권을_양도하면_역할과_owner가_함께_바뀐다() {
        House house = aliveHouse();
        HouseMember requester = ownerRequester(10L);
        HouseMember target = activeTarget(12L, 1L, 8L);
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(requester));
        when(houseMemberRepository.findById(12L)).thenReturn(Optional.of(target));

        TransferOwnershipResponse response = houseMemberCommandService.transferOwnership(7L, 1L, 12L);

        verify(requester).demoteToMember();
        verify(target).promoteToOwner();
        verify(house).changeOwner(target.getUser());
        assertThat(response.houseId()).isEqualTo(1L);
        assertThat(response.newOwnerMembershipId()).isEqualTo(12L);
        assertThat(response.newOwnerUserId()).isEqualTo(8L);
    }

    @Test
    void 소유자가_아니면_403() {
        House house = aliveHouse();
        HouseMember member = mock(HouseMember.class);
        when(member.isActive()).thenReturn(true);
        when(member.isOwner()).thenReturn(false);
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> houseMemberCommandService.transferOwnership(7L, 1L, 12L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_OWNER));
        verify(house, never()).changeOwner(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 비구성원의_양도_시도도_403() {
        House house = aliveHouse();
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseMemberCommandService.transferOwnership(7L, 1L, 12L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_OWNER));
    }

    @Test
    void 대상_membership이_없으면_400() {
        House house = aliveHouse();
        HouseMember requester = ownerRequester(10L);
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(requester));
        when(houseMemberRepository.findById(12L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseMemberCommandService.transferOwnership(7L, 1L, 12L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_TRANSFER_TARGET_INVALID));
    }

    @Test
    void 다른_집_구성원에게는_양도할_수_없다() {
        House house = aliveHouse();
        HouseMember requester = ownerRequester(10L);
        HouseMember otherHouseTarget = activeTarget(12L, 99L, 8L); // 다른 집(99)
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(requester));
        when(houseMemberRepository.findById(12L)).thenReturn(Optional.of(otherHouseTarget));

        assertThatThrownBy(() -> houseMemberCommandService.transferOwnership(7L, 1L, 12L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_TRANSFER_TARGET_INVALID));
        verify(otherHouseTarget, never()).promoteToOwner();
    }

    @Test
    void 자기_자신에게는_양도할_수_없다() {
        House house = aliveHouse();
        HouseMember requester = ownerRequester(10L);
        House sameHouse = mock(House.class);
        when(sameHouse.getId()).thenReturn(1L);
        HouseMember self = mock(HouseMember.class);
        when(self.isActive()).thenReturn(true);
        when(self.getHouse()).thenReturn(sameHouse);
        when(self.getId()).thenReturn(10L); // requester 와 같은 membership
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(requester));
        when(houseMemberRepository.findById(10L)).thenReturn(Optional.of(self));

        assertThatThrownBy(() -> houseMemberCommandService.transferOwnership(7L, 1L, 10L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_TRANSFER_TARGET_INVALID));
    }

    @Test
    void 대상이_LEFT_상태면_400() {
        House house = aliveHouse();
        HouseMember requester = ownerRequester(10L);
        HouseMember leftTarget = mock(HouseMember.class);
        when(leftTarget.isActive()).thenReturn(false); // LEFT - isActive 필터에서 걸러짐
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(requester));
        when(houseMemberRepository.findById(12L)).thenReturn(Optional.of(leftTarget));

        assertThatThrownBy(() -> houseMemberCommandService.transferOwnership(7L, 1L, 12L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_TRANSFER_TARGET_INVALID));
        verify(leftTarget, never()).promoteToOwner();
    }

    @Test
    void 탈퇴한_과거_소유자의_양도_시도는_403() {
        House house = aliveHouse();
        HouseMember leftOwner = mock(HouseMember.class);
        when(leftOwner.isActive()).thenReturn(false); // LEFT row 존재 - isActive 필터에서 걸러짐
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(leftOwner));

        assertThatThrownBy(() -> houseMemberCommandService.transferOwnership(7L, 1L, 12L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_OWNER));
    }

    @Test
    void 삭제된_집은_404() {
        House deleted = mock(House.class);
        when(deleted.isDeleted()).thenReturn(true);
        when(houseRepository.findById(1L)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> houseMemberCommandService.transferOwnership(7L, 1L, 12L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_FOUND));
    }

    @Test
    void 없는_집은_404() {
        when(houseRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseMemberCommandService.transferOwnership(7L, 99L, 12L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_FOUND));
    }
}
