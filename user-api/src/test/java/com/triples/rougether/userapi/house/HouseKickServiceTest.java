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
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseMemberCommandService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HouseKickServiceTest {

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
        when(requester.getId()).thenReturn(membershipId);
        return requester;
    }

    private HouseMember activeTarget(Long membershipId, Long houseId) {
        House targetHouse = mock(House.class);
        when(targetHouse.getId()).thenReturn(houseId);
        HouseMember target = mock(HouseMember.class);
        when(target.isActive()).thenReturn(true);
        when(target.getHouse()).thenReturn(targetHouse);
        lenient().when(target.getId()).thenReturn(membershipId);
        return target;
    }

    @Test
    void 소유자가_강퇴하면_KICKED_전환과_함께_구성원_수가_감소한다() {
        House house = aliveHouse();
        HouseMember requester = ownerRequester(10L);
        HouseMember target = activeTarget(12L, 1L);
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(requester));
        when(houseMemberRepository.findById(12L)).thenReturn(Optional.of(target));

        houseMemberCommandService.kick(7L, 1L, 12L);

        verify(target).kick();
        verify(house).decreaseMemberCount();
    }

    @Test
    void 소유자가_아니면_403() {
        House house = aliveHouse();
        HouseMember member = mock(HouseMember.class);
        when(member.isActive()).thenReturn(true);
        when(member.isOwner()).thenReturn(false);
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> houseMemberCommandService.kick(7L, 1L, 12L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_OWNER));
        verify(house, never()).decreaseMemberCount();
    }

    @Test
    void 자기_자신을_강퇴하면_400() {
        House house = aliveHouse();
        HouseMember requester = ownerRequester(10L);
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(requester));

        assertThatThrownBy(() -> houseMemberCommandService.kick(7L, 1L, 10L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_KICK_SELF));
        verify(house, never()).decreaseMemberCount();
    }

    @Test
    void 대상_membership이_없으면_404() {
        House house = aliveHouse();
        HouseMember requester = ownerRequester(10L);
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(requester));
        when(houseMemberRepository.findById(12L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseMemberCommandService.kick(7L, 1L, 12L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_MEMBER_NOT_FOUND));
    }

    @Test
    void 이미_나간_LEFT_구성원은_강퇴_대상이_아니다() {
        House house = aliveHouse();
        HouseMember requester = ownerRequester(10L);
        HouseMember leftTarget = mock(HouseMember.class);
        when(leftTarget.isActive()).thenReturn(false); // isActive 필터에서 걸러짐
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(requester));
        when(houseMemberRepository.findById(12L)).thenReturn(Optional.of(leftTarget));

        assertThatThrownBy(() -> houseMemberCommandService.kick(7L, 1L, 12L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_MEMBER_NOT_FOUND));
        verify(leftTarget, never()).kick();
    }

    @Test
    void 다른_집_구성원은_강퇴할_수_없다() {
        House house = aliveHouse();
        HouseMember requester = ownerRequester(10L);
        HouseMember otherHouseTarget = activeTarget(12L, 99L);
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(requester));
        when(houseMemberRepository.findById(12L)).thenReturn(Optional.of(otherHouseTarget));

        assertThatThrownBy(() -> houseMemberCommandService.kick(7L, 1L, 12L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_MEMBER_NOT_FOUND));
        verify(otherHouseTarget, never()).kick();
    }

    @Test
    void 없는_집은_404() {
        when(houseRepository.findWithLockById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseMemberCommandService.kick(7L, 99L, 12L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_FOUND));
    }

    @Test
    void 삭제된_집은_404() {
        House deleted = mock(House.class);
        when(deleted.isDeleted()).thenReturn(true);
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> houseMemberCommandService.kick(7L, 1L, 12L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_FOUND));
    }
}
