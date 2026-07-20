package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.repository.HouseGoalRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.userapi.house.dto.HouseMemberListResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseQueryService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HouseMembersServiceTest {

    @Mock private HouseRepository houseRepository;
    @Mock private HouseGoalRepository houseGoalRepository;
    @Mock private HouseMemberRepository houseMemberRepository;
    @InjectMocks private HouseQueryService houseQueryService;

    private House aliveHouse() {
        House house = mock(House.class);
        when(house.isDeleted()).thenReturn(false);
        return house;
    }

    private HouseMember activeMe() {
        HouseMember me = mock(HouseMember.class);
        when(me.isActive()).thenReturn(true);
        return me;
    }

    private HouseMember memberRow(Long membershipId, Long userId, String nickname, HouseMemberRole role) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        when(user.getNickname()).thenReturn(nickname);
        HouseMember member = mock(HouseMember.class);
        when(member.getId()).thenReturn(membershipId);
        when(member.getUser()).thenReturn(user);
        when(member.getRole()).thenReturn(role);
        when(member.getStatus()).thenReturn(HouseMemberStatus.ACTIVE);
        return member;
    }

    @Test
    void 구성원_목록을_가입순으로_내려준다() {
        House house = aliveHouse();
        HouseMember me = activeMe();
        HouseMember ownerRow = memberRow(10L, 7L, "진형", HouseMemberRole.OWNER);
        HouseMember memberRow = memberRow(11L, 8L, "채영", HouseMemberRole.MEMBER);
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(me));
        when(houseMemberRepository.findByHouseIdAndStatusWithUser(1L, HouseMemberStatus.ACTIVE))
                .thenReturn(List.of(ownerRow, memberRow));

        HouseMemberListResponse response = houseQueryService.getMembers(7L, 1L);

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).membershipId()).isEqualTo(10L);
        assertThat(response.items().get(0).nickname()).isEqualTo("진형");
        assertThat(response.items().get(0).role()).isEqualTo(HouseMemberRole.OWNER);
        assertThat(response.items().get(1).userId()).isEqualTo(8L);
        assertThat(response.items().get(1).role()).isEqualTo(HouseMemberRole.MEMBER);
        assertThat(response.items().get(1).status()).isEqualTo(HouseMemberStatus.ACTIVE);
        // 마지막 접속 시각은 user 에서 그대로 내려간다 (갱신 이력 없으면 null)
        assertThat(response.items().get(0).lastAccessedAt()).isNull();
    }

    @Test
    void 온보딩_전_구성원은_닉네임이_null이다() {
        House house = aliveHouse();
        HouseMember me = activeMe();
        HouseMember noNickname = memberRow(10L, 7L, null, HouseMemberRole.OWNER);
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(me));
        when(houseMemberRepository.findByHouseIdAndStatusWithUser(1L, HouseMemberStatus.ACTIVE))
                .thenReturn(List.of(noNickname));

        HouseMemberListResponse response = houseQueryService.getMembers(7L, 1L);

        assertThat(response.items().get(0).nickname()).isNull();
    }

    @Test
    void 구성원이_아니면_403() {
        House house = aliveHouse();
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseQueryService.getMembers(7L, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_MEMBER));
    }

    @Test
    void 탈퇴한_구성원의_목록_조회도_403() {
        House house = aliveHouse();
        HouseMember left = mock(HouseMember.class);
        when(left.isActive()).thenReturn(false);
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(left));

        assertThatThrownBy(() -> houseQueryService.getMembers(7L, 1L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_MEMBER));
    }

    @Test
    void 없는_집은_404() {
        when(houseRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseQueryService.getMembers(7L, 99L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_FOUND));
    }

    @Test
    void 삭제된_집은_404() {
        House house = mock(House.class);
        when(house.isDeleted()).thenReturn(true);
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));

        assertThatThrownBy(() -> houseQueryService.getMembers(7L, 1L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_FOUND));
    }
}
