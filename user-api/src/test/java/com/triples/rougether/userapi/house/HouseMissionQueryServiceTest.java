package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMission;
import com.triples.rougether.domain.house.entity.HouseMissionParticipant;
import com.triples.rougether.domain.house.entity.HouseMissionStatus;
import com.triples.rougether.domain.house.entity.HouseMissionType;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseMissionDailyContributionRepository;
import com.triples.rougether.domain.house.repository.HouseMissionDailyRewardRepository;
import com.triples.rougether.domain.house.repository.HouseMissionParticipantRepository;
import com.triples.rougether.domain.house.repository.HouseMissionRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.userapi.house.dto.HouseMissionListResponse.MissionSummary;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseMissionQueryService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HouseMissionQueryServiceTest {

    @Mock private HouseRepository houseRepository;
    @Mock private HouseMemberRepository houseMemberRepository;
    @Mock private HouseMissionRepository houseMissionRepository;
    @Mock private HouseMissionParticipantRepository participantRepository;
    @Mock private HouseMissionDailyContributionRepository dailyContributionRepository;
    @Mock private HouseMissionDailyRewardRepository dailyRewardRepository;
    @InjectMocks private HouseMissionQueryService houseMissionQueryService;

    @Test
    void 구성원이_아니면_목록_403() {
        aliveHouse();
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseMissionQueryService.getMissions(7L, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_NOT_MEMBER));
        verifyNoInteractions(houseMissionRepository, participantRepository,
                dailyContributionRepository, dailyRewardRepository);
    }

    @Test
    void 미리보기_미션_요약은_구성원_검사_없이_진행도를_반환한다() {
        House house = mock(House.class);
        when(house.getId()).thenReturn(1L);
        HouseMission mission = mission(3L, "공개 미리보기 미션", HouseMissionType.WEEKLY_MEMBER_COUNT, 10);
        when(houseMissionRepository.findByHouseIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(1L))
                .thenReturn(List.of(mission));
        when(participantRepository.sumContributionByMissionIds(List.of(3L)))
                .thenReturn(List.<Object[]>of(new Object[]{3L, 4L}));

        var missions = houseMissionQueryService.getPreviewMissions(house);

        assertThat(missions).singleElement()
                .satisfies(summary -> {
                    assertThat(summary.title()).isEqualTo("공개 미리보기 미션");
                    assertThat(summary.currentValue()).isEqualTo(4);
                    assertThat(summary.targetValue()).isEqualTo(10);
                    assertThat(summary.myContribution()).isNull();
                    assertThat(summary.contributedToday()).isNull();
                    assertThat(summary.todayClaimed()).isNull();
                });
        verify(houseMemberRepository, never()).findByHouseIdAndUserId(any(), any());
        verify(participantRepository, never()).findContributionByMemberIdAndMissionIds(any(), any());
        verifyNoInteractions(dailyContributionRepository, dailyRewardRepository);
    }

    @Test
    void 존재하지_않는_미션은_404() {
        aliveHouse();
        activeMember();
        when(houseMissionRepository.findByIdAndHouseIdAndDeletedAtIsNull(99L, 1L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseMissionQueryService.getMission(7L, 1L, 99L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_MISSION_NOT_FOUND));
        verify(houseMissionRepository).findByIdAndHouseIdAndDeletedAtIsNull(99L, 1L);
        verifyNoInteractions(participantRepository, dailyContributionRepository, dailyRewardRepository);
    }

    @Test
    void 혼합_목록은_조회_순서를_유지하고_진행도와_개인_기여를_일괄_조립한다() {
        House house = aliveHouse();
        when(house.getCurrentMemberCount()).thenReturn(3);
        activeMember();
        HouseMission daily = mission(3L, "일일 미션", HouseMissionType.DAILY_MEMBER_RATE, 50);
        HouseMission weekly = mission(8L, "주간 미션", HouseMissionType.WEEKLY_MEMBER_COUNT, 10);
        HouseMission untouched = mission(2L, "아직 기여 없는 미션", HouseMissionType.WEEKLY_MEMBER_COUNT, 5);
        List<Long> missionIds = List.of(3L, 8L, 2L);
        List<Long> dailyIds = List.of(3L);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        when(houseMissionRepository.findByHouseIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(1L))
                .thenReturn(List.of(daily, weekly, untouched));
        when(participantRepository.sumContributionByMissionIds(missionIds))
                .thenReturn(List.of(new Object[]{8L, 7L}, new Object[]{3L, 99L}));
        when(participantRepository.findContributionByMemberIdAndMissionIds(10L, missionIds))
                .thenReturn(List.of(new Object[]{8L, 2}, new Object[]{3L, 5}));
        when(dailyContributionRepository.findMissionIdsContributedOn(10L, missionIds, today))
                .thenReturn(List.of(8L));
        when(dailyContributionRepository.countActiveByMissionIdsAndDate(dailyIds, today))
                .thenReturn(List.<Object[]>of(new Object[]{3L, 1L}));
        when(dailyRewardRepository.findClaimedMissionIds(dailyIds, today)).thenReturn(List.of(3L));

        var items = houseMissionQueryService.getMissions(7L, 1L).items();

        assertThat(items).extracting(MissionSummary::missionId).containsExactly(3L, 8L, 2L);
        assertThat(items).extracting(MissionSummary::currentValue).containsExactly(33L, 7L, 0L);
        assertThat(items).extracting(MissionSummary::myContribution).containsExactly(5, 2, 0);
        assertThat(items).extracting(MissionSummary::contributedToday).containsExactly(false, true, false);
        assertThat(items).extracting(MissionSummary::todayClaimed).containsExactly(true, null, null);
        verify(participantRepository).sumContributionByMissionIds(missionIds);
        verify(participantRepository).findContributionByMemberIdAndMissionIds(10L, missionIds);
        verify(dailyContributionRepository).findMissionIdsContributedOn(10L, missionIds, today);
        verify(dailyContributionRepository).countActiveByMissionIdsAndDate(dailyIds, today);
        verify(dailyRewardRepository).findClaimedMissionIds(dailyIds, today);
        verify(participantRepository, never()).sumContributionByMissionId(any());
        verify(participantRepository, never()).findByMissionIdAndMemberId(any(), any());
        verify(dailyContributionRepository, never()).countActiveByMissionIdAndContributionDate(any(), any());
        verify(dailyRewardRepository, never()).existsByMissionIdAndRewardDate(any(), any());
    }

    @Test
    void 빈_목록은_진행도와_개인_기여_집계를_생략한다() {
        aliveHouse();
        activeMember();
        when(houseMissionRepository.findByHouseIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(1L))
                .thenReturn(List.of());

        assertThat(houseMissionQueryService.getMissions(7L, 1L).items()).isEmpty();

        verifyNoInteractions(participantRepository, dailyContributionRepository, dailyRewardRepository);
    }

    @Test
    void 삭제된_집은_구성원과_미션_조회_전에_404() {
        House house = aliveHouse();
        when(house.isDeleted()).thenReturn(true);

        assertThatThrownBy(() -> houseMissionQueryService.getMissions(7L, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_NOT_FOUND));

        verifyNoInteractions(houseMemberRepository, houseMissionRepository, participantRepository,
                dailyContributionRepository, dailyRewardRepository);
    }

    @Test
    void 비활성_구성원은_상세_403() {
        aliveHouse();
        HouseMember member = mock(HouseMember.class);
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(member));
        when(member.isActive()).thenReturn(false);

        assertThatThrownBy(() -> houseMissionQueryService.getMission(7L, 1L, 3L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_NOT_MEMBER));

        verifyNoInteractions(houseMissionRepository, participantRepository,
                dailyContributionRepository, dailyRewardRepository);
    }

    @ParameterizedTest
    @CsvSource({"66, true", "67, false"})
    void 일일_상세는_오늘_진행률을_내림하고_목표_경계와_수령_여부를_반환한다(
            int targetPercent, boolean achieved) {
        House house = aliveHouse();
        when(house.getCurrentMemberCount()).thenReturn(3);
        activeMember();
        HouseMission mission = mission(3L, "일일 미션", HouseMissionType.DAILY_MEMBER_RATE, targetPercent);
        when(houseMissionRepository.findByIdAndHouseIdAndDeletedAtIsNull(3L, 1L))
                .thenReturn(Optional.of(mission));
        HouseMissionParticipant participant = mock(HouseMissionParticipant.class);
        when(participant.getContributionValue()).thenReturn(5);
        when(participantRepository.findByMissionIdAndMemberId(3L, 10L)).thenReturn(Optional.of(participant));
        when(dailyContributionRepository.countActiveByMissionIdAndContributionDate(eq(3L), any()))
                .thenReturn(2L);
        when(dailyRewardRepository.existsByMissionIdAndRewardDate(eq(3L), any())).thenReturn(true);

        var response = houseMissionQueryService.getMission(7L, 1L, 3L);

        assertThat(response.currentValue()).isEqualTo(66);
        assertThat(response.myContribution()).isEqualTo(5);
        assertThat(response.achieved()).isEqualTo(achieved);
        assertThat(response.todayClaimed()).isTrue();
        verify(participantRepository, never()).sumContributionByMissionId(any());
    }

    @Test
    void 주간_상세는_누적_기여로_달성을_판정하고_오늘_수령_여부는_비운다() {
        aliveHouse();
        activeMember();
        HouseMission mission = mission(3L, "주간 미션", HouseMissionType.WEEKLY_MEMBER_COUNT, 10);
        when(houseMissionRepository.findByIdAndHouseIdAndDeletedAtIsNull(3L, 1L))
                .thenReturn(Optional.of(mission));
        when(participantRepository.findByMissionIdAndMemberId(3L, 10L)).thenReturn(Optional.empty());
        when(participantRepository.sumContributionByMissionId(3L)).thenReturn(10L);

        var response = houseMissionQueryService.getMission(7L, 1L, 3L);

        assertThat(response.currentValue()).isEqualTo(10);
        assertThat(response.myContribution()).isZero();
        assertThat(response.achieved()).isTrue();
        assertThat(response.todayClaimed()).isNull();
        verifyNoInteractions(dailyContributionRepository, dailyRewardRepository);
    }

    private House aliveHouse() {
        House house = mock(House.class);
        lenient().when(house.getId()).thenReturn(1L);
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        return house;
    }

    private void activeMember() {
        HouseMember member = mock(HouseMember.class);
        when(member.isActive()).thenReturn(true);
        lenient().when(member.getId()).thenReturn(10L);
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(member));
    }

    private HouseMission mission(Long id, String title, HouseMissionType type, int targetValue) {
        HouseMission mission = mock(HouseMission.class);
        when(mission.getId()).thenReturn(id);
        when(mission.getTitle()).thenReturn(title);
        when(mission.getMissionType()).thenReturn(type);
        when(mission.getTargetValue()).thenReturn(targetValue);
        when(mission.getStatus()).thenReturn(HouseMissionStatus.ACTIVE);
        return mission;
    }
}
