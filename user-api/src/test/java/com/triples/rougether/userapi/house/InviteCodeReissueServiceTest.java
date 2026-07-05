package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.goal.repository.GoalRepository;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseGoalRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.dto.InviteCodeResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseCommandService;
import com.triples.rougether.userapi.house.support.InviteCodeGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InviteCodeReissueServiceTest {

    @Mock private HouseRepository houseRepository;
    @Mock private HouseMemberRepository houseMemberRepository;
    @Mock private HouseGoalRepository houseGoalRepository;
    @Mock private GoalRepository goalRepository;
    @Mock private UserRepository userRepository;
    @Mock private InviteCodeGenerator inviteCodeGenerator;
    @InjectMocks private HouseCommandService houseCommandService;

    private HouseMember memberOf(HouseMemberRole role) {
        HouseMember member = mock(HouseMember.class);
        when(member.isActive()).thenReturn(true);
        when(member.getRole()).thenReturn(role);
        return member;
    }

    @Test
    void 소유자는_새_코드를_발급받고_만료가_7일로_갱신된다() {
        House house = mock(House.class);
        when(house.isDeleted()).thenReturn(false);
        when(house.getInviteCode()).thenReturn("WXYZ6789");
        when(house.getInviteExpiresAt()).thenReturn(Instant.now().plus(Duration.ofDays(7)));
        HouseMember owner = memberOf(HouseMemberRole.OWNER);
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(owner));
        when(inviteCodeGenerator.generate()).thenReturn("WXYZ6789");

        InviteCodeResponse response = houseCommandService.reissueInviteCode(7L, 1L);

        ArgumentCaptor<Instant> expiresCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(house).updateInviteCode(org.mockito.ArgumentMatchers.eq("WXYZ6789"), expiresCaptor.capture());
        assertThat(expiresCaptor.getValue())
                .isBetween(Instant.now().plus(Duration.ofDays(7)).minusSeconds(60),
                        Instant.now().plus(Duration.ofDays(7)).plusSeconds(60));
        assertThat(response.inviteCode()).isEqualTo("WXYZ6789");
    }

    @Test
    void 일반_구성원은_403() {
        House house = mock(House.class);
        when(house.isDeleted()).thenReturn(false);
        HouseMember member = memberOf(HouseMemberRole.MEMBER);
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> houseCommandService.reissueInviteCode(7L, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_OWNER));
        verify(house, never()).updateInviteCode(anyString(), any());
    }

    @Test
    void 비구성원도_403_같은_코드로_거부한다() {
        House house = mock(House.class);
        when(house.isDeleted()).thenReturn(false);
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseCommandService.reissueInviteCode(7L, 1L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_OWNER));
    }

    @Test
    void 탈퇴한_소유자_이력도_403() {
        House house = mock(House.class);
        when(house.isDeleted()).thenReturn(false);
        HouseMember leftOwner = mock(HouseMember.class);
        when(leftOwner.isActive()).thenReturn(false);
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(leftOwner));

        assertThatThrownBy(() -> houseCommandService.reissueInviteCode(7L, 1L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_OWNER));
    }

    @Test
    void 없는_집은_404() {
        when(houseRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseCommandService.reissueInviteCode(7L, 99L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_FOUND));
    }

    @Test
    void 삭제된_집은_404() {
        House house = mock(House.class);
        when(house.isDeleted()).thenReturn(true);
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));

        assertThatThrownBy(() -> houseCommandService.reissueInviteCode(7L, 1L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_FOUND));
    }
}
