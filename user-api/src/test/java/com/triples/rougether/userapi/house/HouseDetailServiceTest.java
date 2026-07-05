package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.goal.entity.Goal;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseGoal;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseGoalRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.userapi.house.dto.HouseDetailResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseQueryService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HouseDetailServiceTest {

    @Mock private HouseRepository houseRepository;
    @Mock private HouseGoalRepository houseGoalRepository;
    @Mock private HouseMemberRepository houseMemberRepository;
    @InjectMocks private HouseQueryService houseQueryService;

    private House detailHouse() {
        House house = mock(House.class);
        when(house.isDeleted()).thenReturn(false);
        when(house.getId()).thenReturn(1L);
        when(house.getName()).thenReturn("아침 루틴 하우스");
        when(house.getDescription()).thenReturn("같이 아침 루틴");
        when(house.getMaxMembers()).thenReturn(4);
        when(house.getCurrentMemberCount()).thenReturn(3);
        when(house.getLevel()).thenReturn(2);
        when(house.getGrowthPoints()).thenReturn(120);
        return house;
    }

    private HouseMember memberOf(HouseMemberRole role) {
        HouseMember member = mock(HouseMember.class);
        when(member.isActive()).thenReturn(true);
        when(member.getRole()).thenReturn(role);
        return member;
    }

    private HouseGoal houseGoal(Long goalId, String code, String name) {
        Goal goal = mock(Goal.class);
        when(goal.getId()).thenReturn(goalId);
        when(goal.getCode()).thenReturn(code);
        when(goal.getName()).thenReturn(name);
        HouseGoal houseGoal = mock(HouseGoal.class);
        when(houseGoal.getGoal()).thenReturn(goal);
        return houseGoal;
    }

    @Test
    void 소유자는_초대코드를_포함한_상세를_받는다() {
        House house = detailHouse();
        when(house.getInviteCode()).thenReturn("ABCD2345");
        when(house.getInviteExpiresAt()).thenReturn(Instant.parse("2026-07-10T00:00:00Z"));
        HouseMember owner = memberOf(HouseMemberRole.OWNER);
        HouseGoal morningGoal = houseGoal(1L, "morning_routine", "아침 루틴");
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(owner));
        when(houseGoalRepository.findByHouseIdWithGoal(1L)).thenReturn(List.of(morningGoal));

        HouseDetailResponse response = houseQueryService.getHouseDetail(7L, 1L);

        assertThat(response.name()).isEqualTo("아침 루틴 하우스");
        assertThat(response.growthPoints()).isEqualTo(120);
        assertThat(response.myRole()).isEqualTo(HouseMemberRole.OWNER);
        assertThat(response.inviteCode()).isEqualTo("ABCD2345");
        assertThat(response.inviteExpiresAt()).isNotNull();
        assertThat(response.goals()).hasSize(1);
        assertThat(response.goals().get(0).code()).isEqualTo("morning_routine");
    }

    @Test
    void 일반_구성원에겐_초대코드가_null이다() {
        House house = detailHouse();
        HouseMember member = memberOf(HouseMemberRole.MEMBER);
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(member));
        when(houseGoalRepository.findByHouseIdWithGoal(1L)).thenReturn(List.of());

        HouseDetailResponse response = houseQueryService.getHouseDetail(7L, 1L);

        assertThat(response.myRole()).isEqualTo(HouseMemberRole.MEMBER);
        assertThat(response.inviteCode()).isNull();
        assertThat(response.inviteExpiresAt()).isNull();
    }

    @Test
    void 구성원이_아니면_403() {
        House house = mock(House.class);
        when(house.isDeleted()).thenReturn(false);
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseQueryService.getHouseDetail(7L, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_MEMBER));
    }

    @Test
    void 탈퇴한_구성원도_403() {
        House house = mock(House.class);
        when(house.isDeleted()).thenReturn(false);
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        HouseMember left = mock(HouseMember.class);
        when(left.isActive()).thenReturn(false);
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(left));

        assertThatThrownBy(() -> houseQueryService.getHouseDetail(7L, 1L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_MEMBER));
    }

    @Test
    void 없는_집은_404() {
        when(houseRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseQueryService.getHouseDetail(7L, 99L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_FOUND));
    }

    @Test
    void 삭제된_집은_404() {
        House house = mock(House.class);
        when(house.isDeleted()).thenReturn(true);
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));

        assertThatThrownBy(() -> houseQueryService.getHouseDetail(7L, 1L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_FOUND));
    }
}
