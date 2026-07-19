package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMission;
import com.triples.rougether.domain.house.entity.HouseMissionParticipant;
import com.triples.rougether.domain.house.entity.HouseMissionStatus;
import com.triples.rougether.domain.house.entity.HouseMissionType;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseMissionParticipantRepository;
import com.triples.rougether.domain.house.repository.HouseMissionRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.userapi.house.dto.HouseMissionClaimResponse;
import com.triples.rougether.userapi.house.dto.HouseMissionContributeResponse;
import com.triples.rougether.userapi.house.dto.HouseMissionCreateRequest;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseMissionService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HouseMissionServiceTest {

    @Mock private HouseRepository houseRepository;
    @Mock private HouseMemberRepository houseMemberRepository;
    @Mock private HouseMissionRepository houseMissionRepository;
    @Mock private HouseMissionParticipantRepository participantRepository;
    @InjectMocks private HouseMissionService houseMissionService;

    // NOTE: helper 가 만든 mock 은 반드시 변수에 받은 뒤 바깥 stubbing 에 쓴다 (UnfinishedStubbing 방지).
    private House aliveHouse(Long houseId) {
        House house = mock(House.class);
        when(house.isDeleted()).thenReturn(false);
        when(houseRepository.findById(houseId)).thenReturn(Optional.of(house));
        return house;
    }

    private HouseMember activeMember(Long houseId, Long userId, boolean owner, Long membershipId) {
        HouseMember member = mock(HouseMember.class);
        when(member.isActive()).thenReturn(true);
        lenient().when(member.isOwner()).thenReturn(owner);
        lenient().when(member.getId()).thenReturn(membershipId);
        when(houseMemberRepository.findByHouseIdAndUserId(houseId, userId)).thenReturn(Optional.of(member));
        return member;
    }

    private HouseMission activeMission(Long missionId, Long houseId, int targetValue) {
        HouseMission mission = mock(HouseMission.class);
        lenient().when(mission.isActive()).thenReturn(true);
        lenient().when(mission.isWithinPeriod(any())).thenReturn(true);
        lenient().when(mission.getStatus()).thenReturn(HouseMissionStatus.ACTIVE);
        lenient().when(mission.getTargetValue()).thenReturn(targetValue);
        lenient().when(houseMissionRepository.findByIdAndHouseIdAndDeletedAtIsNull(missionId, houseId))
                .thenReturn(Optional.of(mission));
        lenient().when(houseMissionRepository.findWithLockByIdAndHouseId(missionId, houseId))
                .thenReturn(Optional.of(mission));
        return mission;
    }

    private HouseMissionCreateRequest weeklyRequest() {
        return new HouseMissionCreateRequest(
                "주간 미션", HouseMissionType.WEEKLY_MEMBER_COUNT, 20, null, null);
    }

    @Test
    void 소유자가_미션을_등록한다() {
        aliveHouse(1L);
        activeMember(1L, 7L, true, 10L);
        when(houseMissionRepository.save(any(HouseMission.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = houseMissionService.create(7L, 1L, weeklyRequest());

        assertThat(response.title()).isEqualTo("주간 미션");
        assertThat(response.status()).isEqualTo(HouseMissionStatus.ACTIVE);
        assertThat(response.currentValue()).isZero();
        assertThat(response.achieved()).isFalse();
    }

    @Test
    void 소유자가_아니면_등록_403() {
        aliveHouse(1L);
        activeMember(1L, 7L, false, 10L);

        assertThatThrownBy(() -> houseMissionService.create(7L, 1L, weeklyRequest()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_NOT_OWNER));
        verify(houseMissionRepository, never()).save(any());
    }

    @Test
    void 구성원이_아니면_목록_403() {
        aliveHouse(1L);
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseMissionService.getMissions(7L, 1L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_NOT_MEMBER));
    }

    @Test
    void STREAK_DAYS_는_아직_지원하지_않아_400() {
        aliveHouse(1L);
        activeMember(1L, 7L, true, 10L);
        HouseMissionCreateRequest request = new HouseMissionCreateRequest(
                "연속 미션", HouseMissionType.STREAK_DAYS, 7, null, null);

        assertThatThrownBy(() -> houseMissionService.create(7L, 1L, request))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_MISSION_TYPE_NOT_SUPPORTED));
    }

    @Test
    void 종료가_시작보다_빠르면_400() {
        aliveHouse(1L);
        activeMember(1L, 7L, true, 10L);
        Instant start = Instant.parse("2026-07-10T00:00:00Z");
        HouseMissionCreateRequest request = new HouseMissionCreateRequest(
                "기간 오류", HouseMissionType.WEEKLY_MEMBER_COUNT, 20, start, start.minus(Duration.ofDays(1)));

        assertThatThrownBy(() -> houseMissionService.create(7L, 1L, request))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_MISSION_PERIOD_INVALID));
    }

    @Test
    void 첫_기여는_참여_row_를_만들고_1을_반영한다() {
        aliveHouse(1L);
        activeMember(1L, 7L, false, 10L);
        activeMission(3L, 1L, 2);
        when(participantRepository.findByMissionIdAndMemberId(3L, 10L)).thenReturn(Optional.empty());
        when(participantRepository.save(any(HouseMissionParticipant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(participantRepository.sumContributionByMissionId(3L)).thenReturn(1L);

        HouseMissionContributeResponse response = houseMissionService.contribute(7L, 1L, 3L);

        assertThat(response.myContribution()).isEqualTo(1);
        assertThat(response.currentValue()).isEqualTo(1);
        assertThat(response.achieved()).isFalse();
    }

    @Test
    void 같은_KST_날짜에_두_번_기여하면_409() {
        aliveHouse(1L);
        activeMember(1L, 7L, false, 10L);
        activeMission(3L, 1L, 2);
        HouseMissionParticipant participant = mock(HouseMissionParticipant.class);
        when(participant.getUpdatedAt()).thenReturn(Instant.now());
        when(participantRepository.findByMissionIdAndMemberId(3L, 10L)).thenReturn(Optional.of(participant));

        assertThatThrownBy(() -> houseMissionService.contribute(7L, 1L, 3L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_MISSION_ALREADY_CONTRIBUTED));
        verify(participant, never()).contribute(1);
    }

    @Test
    void 어제_기여했으면_오늘_다시_기여할_수_있다() {
        aliveHouse(1L);
        activeMember(1L, 7L, false, 10L);
        activeMission(3L, 1L, 5);
        HouseMissionParticipant participant = mock(HouseMissionParticipant.class);
        when(participant.getUpdatedAt()).thenReturn(Instant.now().minus(Duration.ofDays(1)));
        when(participant.getContributionValue()).thenReturn(2);
        when(participantRepository.findByMissionIdAndMemberId(3L, 10L)).thenReturn(Optional.of(participant));
        when(participantRepository.sumContributionByMissionId(3L)).thenReturn(2L);

        HouseMissionContributeResponse response = houseMissionService.contribute(7L, 1L, 3L);

        verify(participant).contribute(1);
        assertThat(response.myContribution()).isEqualTo(2);
    }

    @Test
    void 진행_중이_아니면_기여_409() {
        aliveHouse(1L);
        activeMember(1L, 7L, false, 10L);
        HouseMission mission = activeMission(3L, 1L, 2);
        when(mission.isActive()).thenReturn(false);

        assertThatThrownBy(() -> houseMissionService.contribute(7L, 1L, 3L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_MISSION_NOT_ACTIVE));
    }

    @Test
    void 기간_밖이면_기여_409() {
        aliveHouse(1L);
        activeMember(1L, 7L, false, 10L);
        HouseMission mission = activeMission(3L, 1L, 2);
        when(mission.isWithinPeriod(any())).thenReturn(false);

        assertThatThrownBy(() -> houseMissionService.contribute(7L, 1L, 3L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_MISSION_NOT_ACTIVE));
    }

    @Test
    void 목표를_달성한_미션을_claim_하면_성장_포인트_100이_지급된다() {
        House house = aliveHouse(1L);
        activeMember(1L, 7L, false, 10L);
        HouseMission mission = mock(HouseMission.class);
        when(mission.getStatus()).thenReturn(HouseMissionStatus.ACTIVE);
        when(mission.isActive()).thenReturn(true);
        when(mission.getTargetValue()).thenReturn(2);
        when(houseMissionRepository.findWithLockByIdAndHouseId(3L, 1L)).thenReturn(Optional.of(mission));
        when(participantRepository.sumContributionByMissionId(3L)).thenReturn(2L);
        when(houseRepository.findWithLockById(1L)).thenReturn(Optional.of(house));
        when(house.getGrowthPoints()).thenReturn(100);
        when(house.getLevel()).thenReturn(1);
        HouseMissionParticipant participant = mock(HouseMissionParticipant.class);
        when(participantRepository.findByMissionId(3L)).thenReturn(List.of(participant));

        HouseMissionClaimResponse response = houseMissionService.claim(7L, 1L, 3L);

        verify(mission).complete();
        verify(house).addGrowthPoints(100);
        verify(participant).markRewardClaimed();
        assertThat(response.grantedGrowthPoints()).isEqualTo(100);
        assertThat(response.houseGrowthPoints()).isEqualTo(100);
        assertThat(response.houseLevel()).isEqualTo(1);
    }

    @Test
    void 목표_미달성_미션을_claim_하면_409_지급_없음() {
        House house = aliveHouse(1L);
        activeMember(1L, 7L, false, 10L);
        HouseMission mission = mock(HouseMission.class);
        when(mission.getStatus()).thenReturn(HouseMissionStatus.ACTIVE);
        when(mission.isActive()).thenReturn(true);
        when(mission.getTargetValue()).thenReturn(5);
        when(houseMissionRepository.findWithLockByIdAndHouseId(3L, 1L)).thenReturn(Optional.of(mission));
        when(participantRepository.sumContributionByMissionId(3L)).thenReturn(4L);

        assertThatThrownBy(() -> houseMissionService.claim(7L, 1L, 3L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_MISSION_NOT_ACHIEVED));
        verify(mission, never()).complete();
        verify(house, never()).addGrowthPoints(100);
    }

    @Test
    void 이미_COMPLETED_인_미션을_claim_하면_409() {
        House house = aliveHouse(1L);
        activeMember(1L, 7L, false, 10L);
        HouseMission mission = mock(HouseMission.class);
        when(mission.getStatus()).thenReturn(HouseMissionStatus.COMPLETED);
        when(houseMissionRepository.findWithLockByIdAndHouseId(3L, 1L)).thenReturn(Optional.of(mission));

        assertThatThrownBy(() -> houseMissionService.claim(7L, 1L, 3L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_MISSION_ALREADY_CLAIMED));
        verify(house, never()).addGrowthPoints(100);
    }

    @Test
    void 존재하지_않는_미션은_404() {
        aliveHouse(1L);
        activeMember(1L, 7L, false, 10L);
        when(houseMissionRepository.findByIdAndHouseIdAndDeletedAtIsNull(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseMissionService.getMission(7L, 1L, 99L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_MISSION_NOT_FOUND));
    }
}
