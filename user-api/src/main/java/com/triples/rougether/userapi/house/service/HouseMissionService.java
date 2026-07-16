package com.triples.rougether.userapi.house.service;

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
import com.triples.rougether.userapi.house.dto.HouseMissionListResponse;
import com.triples.rougether.userapi.house.dto.HouseMissionListResponse.MissionSummary;
import com.triples.rougether.userapi.house.dto.HouseMissionResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 단체 미션 - 생성(소유자)/목록·상세(구성원)/기여(수행 체크, 하루 1회)/보상(claim, 성장 포인트 +100).
@Service
public class HouseMissionService {

    // 하루 1회 기여 판정 기준 타임존 (서비스 기준 KST, 하드코딩 +9 금지)
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 미션 달성 보상 - 집 성장 포인트 고정 지급 (개인 재화 없음, 팀 확정 2026-07-05)
    private static final int GROWTH_POINTS_PER_MISSION = 100;

    private final HouseRepository houseRepository;
    private final HouseMemberRepository houseMemberRepository;
    private final HouseMissionRepository houseMissionRepository;
    private final HouseMissionParticipantRepository participantRepository;

    public HouseMissionService(HouseRepository houseRepository,
                               HouseMemberRepository houseMemberRepository,
                               HouseMissionRepository houseMissionRepository,
                               HouseMissionParticipantRepository participantRepository) {
        this.houseRepository = houseRepository;
        this.houseMemberRepository = houseMemberRepository;
        this.houseMissionRepository = houseMissionRepository;
        this.participantRepository = participantRepository;
    }

    // 미션 등록 - 소유자 전용. MVP 는 DAILY_MEMBER_RATE/WEEKLY_MEMBER_COUNT 2종만 허용.
    @Transactional
    public HouseMissionResponse create(Long userId, Long houseId, HouseMissionCreateRequest request) {
        House house = requireHouse(houseId);
        HouseMember me = requireActiveMember(userId, houseId);
        if (!me.isOwner()) {
            throw new BusinessException(HouseErrorCode.HOUSE_NOT_OWNER);
        }
        if (request.missionType() == HouseMissionType.STREAK_DAYS) {
            throw new BusinessException(HouseErrorCode.HOUSE_MISSION_TYPE_NOT_SUPPORTED);
        }
        if (request.startsAt() != null && request.endsAt() != null
                && !request.endsAt().isAfter(request.startsAt())) {
            throw new BusinessException(HouseErrorCode.HOUSE_MISSION_PERIOD_INVALID);
        }
        HouseMission mission = houseMissionRepository.save(HouseMission.create(
                house, request.title(), request.missionType(),
                request.targetValue(), request.startsAt(), request.endsAt()));
        return HouseMissionResponse.of(mission, 0, 0);
    }

    // 미션 삭제 - 소유자 전용, soft delete. 보상 수령(COMPLETED) 미션은 성장 포인트가 이미
    // 지급돼 이력 보존을 위해 삭제 불가. claim 과 같은 행 락으로 "claim 중 삭제" 경합을 직렬화한다.
    @Transactional
    public void delete(Long userId, Long houseId, Long missionId) {
        requireHouse(houseId);
        HouseMember me = requireActiveMember(userId, houseId);
        if (!me.isOwner()) {
            throw new BusinessException(HouseErrorCode.HOUSE_NOT_OWNER);
        }
        HouseMission mission = houseMissionRepository.findWithLockByIdAndHouseId(missionId, houseId)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_MISSION_NOT_FOUND));
        if (mission.getStatus() == HouseMissionStatus.COMPLETED) {
            throw new BusinessException(HouseErrorCode.HOUSE_MISSION_ALREADY_CLAIMED);
        }
        mission.softDelete(Instant.now());
    }

    // 미션 목록 - 구성원 전용, 최신 생성순. currentValue 는 기여 합산을 일괄 조회(N+1 회피).
    @Transactional(readOnly = true)
    public HouseMissionListResponse getMissions(Long userId, Long houseId) {
        requireHouse(houseId);
        requireActiveMember(userId, houseId);
        List<HouseMission> missions = houseMissionRepository.findByHouseIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(houseId);
        Map<Long, Long> sums = sumByMissionIds(missions);
        List<MissionSummary> items = missions.stream()
                .map(mission -> MissionSummary.of(mission, sums.getOrDefault(mission.getId(), 0L)))
                .toList();
        return new HouseMissionListResponse(items);
    }

    // 미션 상세 - 구성원 전용. 내 기여(myContribution) 포함.
    @Transactional(readOnly = true)
    public HouseMissionResponse getMission(Long userId, Long houseId, Long missionId) {
        requireHouse(houseId);
        HouseMember me = requireActiveMember(userId, houseId);
        HouseMission mission = requireMission(missionId, houseId);
        long currentValue = participantRepository.sumContributionByMissionId(missionId);
        int myContribution = participantRepository.findByMissionIdAndMemberId(missionId, me.getId())
                .map(HouseMissionParticipant::getContributionValue)
                .orElse(0);
        return HouseMissionResponse.of(mission, currentValue, myContribution);
    }

    // 미션 수행 체크(기여) - 본인 +1, KST 하루 1회. 구성원이 공동 미션 자체를 직접 수행 체크한다 (모델 확정 2026-07-05).
    // 삭제·claim 과 같은 미션 행 락으로 직렬화 — 비잠금 조회면 "삭제 커밋 직전 읽은 미션"에 기여가 기록될 수 있다.
    @Transactional
    public HouseMissionContributeResponse contribute(Long userId, Long houseId, Long missionId) {
        requireHouse(houseId);
        HouseMember me = requireActiveMember(userId, houseId);
        HouseMission mission = houseMissionRepository.findWithLockByIdAndHouseId(missionId, houseId)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_MISSION_NOT_FOUND));
        Instant now = Instant.now();
        if (!mission.isActive() || !mission.isWithinPeriod(now)) {
            throw new BusinessException(HouseErrorCode.HOUSE_MISSION_NOT_ACTIVE);
        }
        HouseMissionParticipant participant = participantRepository
                .findByMissionIdAndMemberId(missionId, me.getId())
                .orElse(null);
        if (participant != null && isSameKstDay(participant.getUpdatedAt(), now)) {
            throw new BusinessException(HouseErrorCode.HOUSE_MISSION_ALREADY_CONTRIBUTED);
        }
        if (participant == null) {
            participant = participantRepository.save(HouseMissionParticipant.create(mission, me));
        }
        participant.contribute(1);
        // JPQL 실행 전 auto-flush 로 이번 +1 이 합산에 반영된다.
        long currentValue = participantRepository.sumContributionByMissionId(missionId);
        return new HouseMissionContributeResponse(
                missionId, participant.getContributionValue(), currentValue,
                currentValue >= mission.getTargetValue());
    }

    // 보상 받기 - 구성원 누구나 최초 1회. 미션 행 락으로 동시 claim 이중 지급을 막고,
    // COMPLETED 전환 + 집 성장 포인트 +100 + 참여자 reward_claimed 일괄 처리(한 트랜잭션).
    @Transactional
    public HouseMissionClaimResponse claim(Long userId, Long houseId, Long missionId) {
        requireHouse(houseId);
        requireActiveMember(userId, houseId);
        HouseMission mission = houseMissionRepository.findWithLockByIdAndHouseId(missionId, houseId)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_MISSION_NOT_FOUND));
        if (mission.getStatus() == HouseMissionStatus.COMPLETED) {
            throw new BusinessException(HouseErrorCode.HOUSE_MISSION_ALREADY_CLAIMED);
        }
        if (!mission.isActive()) {
            throw new BusinessException(HouseErrorCode.HOUSE_MISSION_NOT_ACTIVE);
        }
        long currentValue = participantRepository.sumContributionByMissionId(missionId);
        if (currentValue < mission.getTargetValue()) {
            throw new BusinessException(HouseErrorCode.HOUSE_MISSION_NOT_ACHIEVED);
        }
        mission.complete();
        // 성장 포인트는 집 행 락으로 갱신 - 다른 미션의 동시 claim 과 lost update 방지.
        House house = houseRepository.findWithLockById(houseId)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_FOUND));
        house.addGrowthPoints(GROWTH_POINTS_PER_MISSION);
        participantRepository.findByMissionId(missionId)
                .forEach(HouseMissionParticipant::markRewardClaimed);
        return new HouseMissionClaimResponse(
                missionId, mission.getStatus(), GROWTH_POINTS_PER_MISSION,
                house.getGrowthPoints(), house.getLevel());
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

    private static boolean isSameKstDay(Instant lastContributedAt, Instant now) {
        LocalDate last = lastContributedAt.atZone(KST).toLocalDate();
        return last.equals(now.atZone(KST).toLocalDate());
    }
}
