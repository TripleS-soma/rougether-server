package com.triples.rougether.userapi.house.service;

import static com.triples.rougether.userapi.house.service.HouseMissionProgressCalculator.isDailyAchieved;
import static com.triples.rougether.userapi.house.service.HouseMissionProgressCalculator.ratePercent;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMission;
import com.triples.rougether.domain.house.entity.HouseMissionParticipant;
import com.triples.rougether.domain.house.entity.HouseMissionType;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseMissionDailyContributionRepository;
import com.triples.rougether.domain.house.repository.HouseMissionDailyRewardRepository;
import com.triples.rougether.domain.house.repository.HouseMissionParticipantRepository;
import com.triples.rougether.domain.house.repository.HouseMissionRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.userapi.house.dto.HouseMissionListResponse;
import com.triples.rougether.userapi.house.dto.HouseMissionListResponse.MissionSummary;
import com.triples.rougether.userapi.house.dto.HouseMissionResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 집 미션의 목록, 상세와 미리보기 응답을 조회하고 조립함.
@Service
@Transactional(readOnly = true)
public class HouseMissionQueryService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final HouseRepository houseRepository;
    private final HouseMemberRepository houseMemberRepository;
    private final HouseMissionRepository houseMissionRepository;
    private final HouseMissionParticipantRepository participantRepository;
    private final HouseMissionDailyContributionRepository dailyContributionRepository;
    private final HouseMissionDailyRewardRepository dailyRewardRepository;

    public HouseMissionQueryService(HouseRepository houseRepository,
                                    HouseMemberRepository houseMemberRepository,
                                    HouseMissionRepository houseMissionRepository,
                                    HouseMissionParticipantRepository participantRepository,
                                    HouseMissionDailyContributionRepository dailyContributionRepository,
                                    HouseMissionDailyRewardRepository dailyRewardRepository) {
        this.houseRepository = houseRepository;
        this.houseMemberRepository = houseMemberRepository;
        this.houseMissionRepository = houseMissionRepository;
        this.participantRepository = participantRepository;
        this.dailyContributionRepository = dailyContributionRepository;
        this.dailyRewardRepository = dailyRewardRepository;
    }

    // 구성원 목록을 최신 생성순으로 반환함. 진행률과 개인 기여는 일괄 조회함.
    public HouseMissionListResponse getMissions(Long userId, Long houseId) {
        House house = requireHouse(houseId);
        HouseMember me = requireActiveMember(userId, houseId);
        return new HouseMissionListResponse(summarizeMissions(house, me.getId()));
    }

    // 미리보기 호출자가 집 존재와 접근 권한을 확인한 뒤 전달함. 개인 기여값은 생략함.
    public List<MissionSummary> getPreviewMissions(House house) {
        return summarizeMissions(house, null);
    }

    // 구성원 전용 상세를 반환함. myContribution은 두 유형 모두 누적 체크 횟수임.
    public HouseMissionResponse getMission(Long userId, Long houseId, Long missionId) {
        House house = requireHouse(houseId);
        HouseMember me = requireActiveMember(userId, houseId);
        HouseMission mission = requireMission(missionId, houseId);
        int myContribution = participantRepository.findByMissionIdAndMemberId(missionId, me.getId())
                .map(HouseMissionParticipant::getContributionValue)
                .orElse(0);
        if (mission.getMissionType() == HouseMissionType.DAILY_MEMBER_RATE) {
            LocalDate today = LocalDate.now(KST);
            long todayCount = dailyContributionRepository.countActiveByMissionIdAndContributionDate(missionId, today);
            return HouseMissionResponse.of(mission,
                    ratePercent(todayCount, house.getCurrentMemberCount()),
                    myContribution,
                    isDailyAchieved(todayCount, house.getCurrentMemberCount(), mission.getTargetValue()),
                    dailyRewardRepository.existsByMissionIdAndRewardDate(missionId, today));
        }
        long currentValue = participantRepository.sumContributionByMissionId(missionId);
        return HouseMissionResponse.of(mission, currentValue, myContribution);
    }

    // memberId가 있으면 개인 기여를 포함함. 미리보기는 null을 전달해 개인 조회를 생략함.
    private List<MissionSummary> summarizeMissions(House house, Long memberId) {
        Long houseId = house.getId();
        List<HouseMission> missions = houseMissionRepository.findByHouseIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(houseId);
        Map<Long, Long> sums = sumByMissionIds(missions);

        LocalDate today = LocalDate.now(KST);
        Map<Long, Integer> myContributions = myContributionsByMissionIds(memberId, missions);
        Set<Long> contributedTodayIds = missionIdsContributedOn(memberId, missions, today);
        List<Long> dailyIds = missions.stream()
                .filter(mission -> mission.getMissionType() == HouseMissionType.DAILY_MEMBER_RATE)
                .map(HouseMission::getId)
                .toList();
        Map<Long, Long> todayCounts = countTodayByMissionIds(dailyIds, today);
        Set<Long> todayClaimedIds = dailyIds.isEmpty()
                ? Set.of()
                : Set.copyOf(dailyRewardRepository.findClaimedMissionIds(dailyIds, today));

        return missions.stream()
                .map(mission -> {
                    long currentValue = mission.getMissionType() == HouseMissionType.DAILY_MEMBER_RATE
                            ? ratePercent(todayCounts.getOrDefault(mission.getId(), 0L), house.getCurrentMemberCount())
                            : sums.getOrDefault(mission.getId(), 0L);
                    boolean daily = mission.getMissionType() == HouseMissionType.DAILY_MEMBER_RATE;
                    boolean todayClaimed = todayClaimedIds.contains(mission.getId());
                    if (memberId == null) {
                        return daily
                                ? MissionSummary.ofDaily(mission, currentValue, todayClaimed)
                                : MissionSummary.of(mission, currentValue);
                    }
                    int myContribution = myContributions.getOrDefault(mission.getId(), 0);
                    boolean contributedToday = contributedTodayIds.contains(mission.getId());
                    return daily
                            ? MissionSummary.ofDaily(mission, currentValue, todayClaimed, myContribution, contributedToday)
                            : MissionSummary.of(mission, currentValue, myContribution, contributedToday);
                })
                .toList();
    }

    // 내 누적 기여를 일괄 조회함. 참여 행이 없으면 응답 조립 시 0으로 처리함.
    private Map<Long, Integer> myContributionsByMissionIds(Long memberId, List<HouseMission> missions) {
        if (memberId == null || missions.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = missions.stream().map(HouseMission::getId).toList();
        Map<Long, Integer> contributions = new HashMap<>();
        for (Object[] row : participantRepository.findContributionByMemberIdAndMissionIds(memberId, ids)) {
            contributions.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return contributions;
    }

    // 두 유형 모두 일별 이력이 남으므로 오늘 기여한 미션 ID를 한 번에 조회함.
    private Set<Long> missionIdsContributedOn(Long memberId, List<HouseMission> missions, LocalDate today) {
        if (memberId == null || missions.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = missions.stream().map(HouseMission::getId).toList();
        return Set.copyOf(dailyContributionRepository.findMissionIdsContributedOn(memberId, ids, today));
    }

    private House requireHouse(Long houseId) {
        return houseRepository.findById(houseId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_FOUND));
    }

    private HouseMember requireActiveMember(Long userId, Long houseId) {
        return houseMemberRepository.findByHouseIdAndUserId(houseId, userId)
                .filter(HouseMember::isActive)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_MEMBER));
    }

    private HouseMission requireMission(Long missionId, Long houseId) {
        return houseMissionRepository.findByIdAndHouseIdAndDeletedAtIsNull(missionId, houseId)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_MISSION_NOT_FOUND));
    }

    private Map<Long, Long> sumByMissionIds(List<HouseMission> missions) {
        if (missions.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = missions.stream().map(HouseMission::getId).toList();
        Map<Long, Long> sums = new HashMap<>();
        for (Object[] row : participantRepository.sumContributionByMissionIds(ids)) {
            sums.put((Long) row[0], (Long) row[1]);
        }
        return sums;
    }

    private Map<Long, Long> countTodayByMissionIds(List<Long> missionIds, LocalDate today) {
        if (missionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : dailyContributionRepository.countActiveByMissionIdsAndDate(missionIds, today)) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }
}
