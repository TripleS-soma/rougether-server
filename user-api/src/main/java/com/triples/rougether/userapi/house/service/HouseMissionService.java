package com.triples.rougether.userapi.house.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.entity.HouseMission;
import com.triples.rougether.domain.house.entity.HouseMissionParticipant;
import com.triples.rougether.domain.house.entity.HouseMissionStatus;
import com.triples.rougether.domain.house.entity.HouseMissionType;
import com.triples.rougether.domain.house.entity.HouseMissionDailyContribution;
import com.triples.rougether.domain.house.entity.HouseMissionDailyReward;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseMissionDailyContributionRepository;
import com.triples.rougether.domain.house.repository.HouseMissionDailyRewardRepository;
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
import com.triples.rougether.userapi.notification.message.NotificationMessages;
import com.triples.rougether.userapi.notification.service.NotificationService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 단체 미션 - 생성(소유자)/목록·상세(구성원)/기여(수행 체크, 하루 1회)/보상(claim).
// WEEKLY: 기여 누적 합 >= target, 1회 claim(+100) 후 COMPLETED. (모델 확정 2026-07-05)
// DAILY: 오늘(KST) 기여 멤버 비율 >= target%, 매일 claim(+20) 반복, COMPLETED 전환 없음. (#201, 확정 2026-07-23)
@Service
public class HouseMissionService {

    // 하루 1회 기여 판정 기준 타임존 (서비스 기준 KST, 하드코딩 +9 금지)
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // WEEKLY 미션 달성 보상 - 집 성장 포인트 고정 지급 (개인 재화 없음, 팀 확정 2026-07-05)
    private static final int GROWTH_POINTS_PER_MISSION = 100;
    // DAILY 미션 일일 달성 보상 - WEEKLY 의 1/5 (확정 2026-07-23)
    private static final int GROWTH_POINTS_PER_DAILY_MISSION = 20;
    // DAILY 미션 target 은 달성률 %(1~100)
    private static final int DAILY_TARGET_MAX_PERCENT = 100;

    private final HouseRepository houseRepository;
    private final HouseMemberRepository houseMemberRepository;
    private final HouseMissionRepository houseMissionRepository;
    private final HouseMissionParticipantRepository participantRepository;
    private final HouseMissionDailyContributionRepository dailyContributionRepository;
    private final HouseMissionDailyRewardRepository dailyRewardRepository;
    private final NotificationService notificationService;

    public HouseMissionService(HouseRepository houseRepository,
                               HouseMemberRepository houseMemberRepository,
                               HouseMissionRepository houseMissionRepository,
                               HouseMissionParticipantRepository participantRepository,
                               HouseMissionDailyContributionRepository dailyContributionRepository,
                               HouseMissionDailyRewardRepository dailyRewardRepository,
                               NotificationService notificationService) {
        this.houseRepository = houseRepository;
        this.houseMemberRepository = houseMemberRepository;
        this.houseMissionRepository = houseMissionRepository;
        this.participantRepository = participantRepository;
        this.dailyContributionRepository = dailyContributionRepository;
        this.dailyRewardRepository = dailyRewardRepository;
        this.notificationService = notificationService;
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
        if (request.missionType() == HouseMissionType.DAILY_MEMBER_RATE
                && request.targetValue() > DAILY_TARGET_MAX_PERCENT) {
            throw new BusinessException(HouseErrorCode.HOUSE_MISSION_TARGET_INVALID);
        }
        if (request.startsAt() != null && request.endsAt() != null
                && !request.endsAt().isAfter(request.startsAt())) {
            throw new BusinessException(HouseErrorCode.HOUSE_MISSION_PERIOD_INVALID);
        }
        HouseMission mission = houseMissionRepository.save(HouseMission.create(
                house, request.title(), request.missionType(),
                request.targetValue(), request.startsAt(), request.endsAt()));
        if (mission.getMissionType() == HouseMissionType.DAILY_MEMBER_RATE) {
            return HouseMissionResponse.of(mission, 0, 0, false, false);
        }
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

    // 미션 목록 - 구성원 전용, 최신 생성순. currentValue 는 기여 합산(WEEKLY)·오늘 달성률 %(DAILY)를 일괄 조회(N+1 회피).
    @Transactional(readOnly = true)
    public HouseMissionListResponse getMissions(Long userId, Long houseId) {
        House house = requireHouse(houseId);
        requireActiveMember(userId, houseId);
        List<HouseMission> missions = houseMissionRepository.findByHouseIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(houseId);
        Map<Long, Long> sums = sumByMissionIds(missions);

        LocalDate today = LocalDate.now(KST);
        List<Long> dailyIds = missions.stream()
                .filter(mission -> mission.getMissionType() == HouseMissionType.DAILY_MEMBER_RATE)
                .map(HouseMission::getId)
                .toList();
        Map<Long, Long> todayCounts = countTodayByMissionIds(dailyIds, today);
        Set<Long> todayClaimedIds = dailyIds.isEmpty()
                ? Set.of()
                : Set.copyOf(dailyRewardRepository.findClaimedMissionIds(dailyIds, today));

        List<MissionSummary> items = missions.stream()
                .map(mission -> {
                    if (mission.getMissionType() == HouseMissionType.DAILY_MEMBER_RATE) {
                        long todayCount = todayCounts.getOrDefault(mission.getId(), 0L);
                        return MissionSummary.ofDaily(mission,
                                ratePercent(todayCount, house.getCurrentMemberCount()),
                                todayClaimedIds.contains(mission.getId()));
                    }
                    return MissionSummary.of(mission, sums.getOrDefault(mission.getId(), 0L));
                })
                .toList();
        return new HouseMissionListResponse(items);
    }

    // 미션 상세 - 구성원 전용. 내 기여(myContribution)는 두 유형 모두 누적 체크 횟수.
    @Transactional(readOnly = true)
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

    // 미션 수행 체크(기여) - 본인 +1, KST 하루 1회. 구성원이 공동 미션 자체를 직접 수행 체크한다 (모델 확정 2026-07-05).
    // 삭제·claim 과 같은 미션 행 락으로 직렬화 — 비잠금 조회면 "삭제 커밋 직전 읽은 미션"에 기여가 기록될 수 있다.
    // 하루 1회는 일별 이력 UNIQUE(mission, membership, date)가 DB 방어선 (#201 — updated_at 추정에서 전환).
    @Transactional
    public HouseMissionContributeResponse contribute(Long userId, Long houseId, Long missionId) {
        House house = requireHouse(houseId);
        HouseMember me = requireActiveMember(userId, houseId);
        HouseMission mission = houseMissionRepository.findWithLockByIdAndHouseId(missionId, houseId)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_MISSION_NOT_FOUND));
        Instant now = Instant.now();
        if (!mission.isActive() || !mission.isWithinPeriod(now)) {
            throw new BusinessException(HouseErrorCode.HOUSE_MISSION_NOT_ACTIVE);
        }
        LocalDate today = now.atZone(KST).toLocalDate();
        if (dailyContributionRepository.existsByMissionIdAndMemberIdAndContributionDate(
                missionId, me.getId(), today)) {
            throw new BusinessException(HouseErrorCode.HOUSE_MISSION_ALREADY_CONTRIBUTED);
        }
        HouseMissionParticipant participant = participantRepository
                .findByMissionIdAndMemberId(missionId, me.getId())
                .orElseGet(() -> participantRepository.save(HouseMissionParticipant.create(mission, me)));
        participant.contribute(1);
        try {
            // 미션 행 락으로 직렬화되지만, UNIQUE 를 최후 방어선으로 유지(즉시 flush 로 이 지점에서 감지).
            dailyContributionRepository.saveAndFlush(
                    HouseMissionDailyContribution.record(mission, me, today));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(HouseErrorCode.HOUSE_MISSION_ALREADY_CONTRIBUTED);
        }

        if (mission.getMissionType() == HouseMissionType.DAILY_MEMBER_RATE) {
            long todayCount = dailyContributionRepository.countActiveByMissionIdAndContributionDate(missionId, today);
            return new HouseMissionContributeResponse(
                    missionId, participant.getContributionValue(),
                    ratePercent(todayCount, house.getCurrentMemberCount()),
                    isDailyAchieved(todayCount, house.getCurrentMemberCount(), mission.getTargetValue()));
        }
        // JPQL 실행 전 auto-flush 로 이번 +1 이 합산에 반영된다.
        long currentValue = participantRepository.sumContributionByMissionId(missionId);
        boolean achieved = currentValue >= mission.getTargetValue();
        // 이번 +1 로 처음 도달한 순간에만 1회 발송. 미션 행 락 하 판정이라 별도 dedupe 쿼리가 필요없음
        if (achieved && currentValue - 1 < mission.getTargetValue()) {
            notifyAchieved(houseId, mission);
        }
        return new HouseMissionContributeResponse(
                missionId, participant.getContributionValue(), currentValue, achieved);
    }

    // 보상 받기 - 미션 행 락으로 동시 claim 이중 지급을 막는다.
    // WEEKLY: 구성원 누구나 미션당 최초 1회. COMPLETED 전환 + 성장 포인트 +100 + reward_claimed 일괄(한 트랜잭션).
    // DAILY: 그날(KST) 달성 시 구성원 누구나 하루 1회. +20 지급, COMPLETED 전환 없이 매일 반복.
    //        하루 1회는 일별 보상 UNIQUE(mission, reward_date)가 DB 방어선. 자정이 지나면 그날 보상은 소멸(소급 없음).
    // house 는 트랜잭션의 첫 접근부터 락 조회로 읽는다 - 일반 조회로 영속성 컨텍스트에 올라간 뒤
    // 락을 다시 잡으면 낡은 관리 엔티티가 반환되어(자동 refresh 없음) 서로 다른 미션의 동시 claim 이
    // 성장 포인트를 덮어쓸 수 있다(lost update). 락 순서는 house -> mission 으로 claim 경로에서 일관.
    @Transactional
    public HouseMissionClaimResponse claim(Long userId, Long houseId, Long missionId) {
        House house = houseRepository.findWithLockById(houseId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_FOUND));
        HouseMember me = requireActiveMember(userId, houseId);
        HouseMission mission = houseMissionRepository.findWithLockByIdAndHouseId(missionId, houseId)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_MISSION_NOT_FOUND));
        if (mission.getMissionType() == HouseMissionType.DAILY_MEMBER_RATE) {
            return claimDaily(house, me, mission);
        }
        if (mission.getStatus() == HouseMissionStatus.COMPLETED) {
            throw new BusinessException(HouseErrorCode.HOUSE_MISSION_ALREADY_CLAIMED);
        }
        // 만료 후 claim 불가(유예 없음, #205) - 기간이 끝나면 보상 기회도 끝난다. EXPIRED 전이 배치 전이라도 기간으로 거부.
        if (!mission.isActive() || !mission.isWithinPeriod(Instant.now())) {
            throw new BusinessException(HouseErrorCode.HOUSE_MISSION_NOT_ACTIVE);
        }
        long currentValue = participantRepository.sumContributionByMissionId(missionId);
        if (currentValue < mission.getTargetValue()) {
            throw new BusinessException(HouseErrorCode.HOUSE_MISSION_NOT_ACHIEVED);
        }
        mission.complete();
        house.addGrowthPoints(GROWTH_POINTS_PER_MISSION);
        participantRepository.findByMissionId(missionId)
                .forEach(HouseMissionParticipant::markRewardClaimed);
        return new HouseMissionClaimResponse(
                missionId, mission.getStatus(), GROWTH_POINTS_PER_MISSION,
                house.getGrowthPoints(), house.getLevel());
    }

    // 목표 도달 알림 - 집 활성 멤버 전원(마지막 기여자 본인 포함). 기여와 같은 트랜잭션에서 동기 저장(응원 #174 패턴).
    private void notifyAchieved(Long houseId, HouseMission mission) {
        var content = NotificationMessages.houseMissionAchieved(mission.getTitle());
        houseMemberRepository.findByHouseIdAndStatusWithUser(houseId, HouseMemberStatus.ACTIVE)
                .forEach(member -> notificationService.send(member.getUser().getId(), content, mission.getId()));
    }

    private HouseMissionClaimResponse claimDaily(House house, HouseMember me, HouseMission mission) {
        Instant now = Instant.now();
        if (!mission.isActive() || !mission.isWithinPeriod(now)) {
            throw new BusinessException(HouseErrorCode.HOUSE_MISSION_NOT_ACTIVE);
        }
        LocalDate today = now.atZone(KST).toLocalDate();
        if (dailyRewardRepository.existsByMissionIdAndRewardDate(mission.getId(), today)) {
            throw dailyAlreadyClaimed();
        }
        // 판정에 쓰는 멤버 수도 claim() 진입 시 잠근 house 의 값을 쓴다.
        long todayCount = dailyContributionRepository.countActiveByMissionIdAndContributionDate(mission.getId(), today);
        if (!isDailyAchieved(todayCount, house.getCurrentMemberCount(), mission.getTargetValue())) {
            throw new BusinessException(HouseErrorCode.HOUSE_MISSION_NOT_ACHIEVED);
        }
        try {
            // 미션 행 락으로 직렬화되지만, UNIQUE 를 최후 방어선으로 유지(즉시 flush 로 이 지점에서 감지).
            dailyRewardRepository.saveAndFlush(HouseMissionDailyReward.claim(mission, today, me));
        } catch (DataIntegrityViolationException e) {
            throw dailyAlreadyClaimed();
        }
        house.addGrowthPoints(GROWTH_POINTS_PER_DAILY_MISSION);
        return new HouseMissionClaimResponse(
                mission.getId(), mission.getStatus(), GROWTH_POINTS_PER_DAILY_MISSION,
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

    // 같은 코드(ALREADY_CLAIMED)를 재사용하되 DAILY 는 "오늘" 기준임을 메시지로 구분 (#201 결정값)
    private static BusinessException dailyAlreadyClaimed() {
        return new BusinessException(HouseErrorCode.HOUSE_MISSION_ALREADY_CLAIMED,
                "오늘은 이미 보상을 받았습니다. 내일 다시 도전할 수 있습니다.");
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

    // DAILY 진행 표시값: 오늘 기여 멤버 비율 %(내림). 멤버 0명(이론상 없음)은 0 처리.
    private static long ratePercent(long todayCount, int memberCount) {
        if (memberCount <= 0) {
            return 0;
        }
        return todayCount * 100 / memberCount;
    }

    // DAILY 달성 판정: 정수 산술로 (기여수/멤버수 >= target%) — 부동소수 오차 회피.
    private static boolean isDailyAchieved(long todayCount, int memberCount, int targetPercent) {
        return memberCount > 0 && todayCount * 100 >= (long) targetPercent * memberCount;
    }
}
