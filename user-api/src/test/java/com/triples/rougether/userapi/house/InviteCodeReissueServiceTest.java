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
import com.triples.rougether.userapi.house.service.HouseCoverImageCatalog;
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
    @Mock private HouseCoverImageCatalog houseCoverImageCatalog;
    @InjectMocks private HouseCommandService houseCommandService;

    private HouseMember memberOf(HouseMemberRole role) {
        HouseMember member = mock(HouseMember.class);
        when(member.isActive()).thenReturn(true);
        when(member.isOwner()).thenReturn(role == HouseMemberRole.OWNER);
        return member;
    }

    @Test
    void 소유자는_새_코드를_발급받고_만료가_7일로_갱신된다() {
        House house = mock(House.class);
        when(house.isDeleted()).thenReturn(false);
        when(house.getInviteCode()).thenReturn("WXYZ6789");
        when(house.getInviteExpiresAt()).thenReturn(Instant.now().plus(Duration.ofDays(7)));
        HouseMember owner = memberOf(HouseMemberRole.OWNER);
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));
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
    void 일반_구성원은_본인_개인_코드를_발급받고_집_공용_코드는_바뀌지_않는다() {
        House house = mock(House.class);
        when(house.isDeleted()).thenReturn(false);
        HouseMember member = memberOf(HouseMemberRole.MEMBER);
        when(member.getInviteCode()).thenReturn("MBRC2345");
        when(member.getInviteExpiresAt()).thenReturn(Instant.now().plus(Duration.ofDays(7)));
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(member));
        when(inviteCodeGenerator.generate()).thenReturn("MBRC2345");

        InviteCodeResponse response = houseCommandService.reissueInviteCode(7L, 1L);

        ArgumentCaptor<Instant> expiresCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(member).updateInviteCode(org.mockito.ArgumentMatchers.eq("MBRC2345"), expiresCaptor.capture());
        assertThat(expiresCaptor.getValue())
                .isBetween(Instant.now().plus(Duration.ofDays(7)).minusSeconds(60),
                        Instant.now().plus(Duration.ofDays(7)).plusSeconds(60));
        assertThat(response.inviteCode()).isEqualTo("MBRC2345");
        verify(house, never()).updateInviteCode(anyString(), any());
    }

    @Test
    void 비구성원은_403() {
        House house = mock(House.class);
        when(house.isDeleted()).thenReturn(false);
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseCommandService.reissueInviteCode(7L, 1L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_MEMBER));
    }

    @Test
    void 탈퇴한_구성원_이력도_403() {
        House house = mock(House.class);
        when(house.isDeleted()).thenReturn(false);
        HouseMember leftMember = mock(HouseMember.class);
        when(leftMember.isActive()).thenReturn(false);
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(leftMember));

        assertThatThrownBy(() -> houseCommandService.reissueInviteCode(7L, 1L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_MEMBER));
    }

    @Test
    void 없는_집은_404() {
        when(houseRepository.findWithLockById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseCommandService.reissueInviteCode(7L, 99L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_FOUND));
    }

    @Test
    void 삭제된_집은_404() {
        House house = mock(House.class);
        when(house.isDeleted()).thenReturn(true);
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));

        assertThatThrownBy(() -> houseCommandService.reissueInviteCode(7L, 1L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_FOUND));
    }
}
