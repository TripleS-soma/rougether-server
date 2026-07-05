package com.triples.rougether.userapi.house.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseGoal;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.repository.HouseGoalRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.userapi.house.dto.HouseDetailResponse;
import com.triples.rougether.userapi.house.dto.HouseListResponse;
import com.triples.rougether.userapi.house.dto.HouseListResponse.GoalSummary;
import com.triples.rougether.userapi.house.dto.HouseMemberListResponse;
import com.triples.rougether.userapi.house.dto.HouseMemberListResponse.MemberSummary;
import com.triples.rougether.userapi.house.dto.HouseListResponse.HouseSummary;
import com.triples.rougether.userapi.house.dto.MyHouseListResponse;
import com.triples.rougether.userapi.house.dto.MyHouseListResponse.MyHouseSummary;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 집 탐색 목록. 최신 생성순 기본, goalCode 필터 1차 지원. 탐색·추천 겸용(별도 추천 엔드포인트 없음).
@Service
public class HouseQueryService {

    private final HouseRepository houseRepository;
    private final HouseGoalRepository houseGoalRepository;
    private final HouseMemberRepository houseMemberRepository;

    public HouseQueryService(HouseRepository houseRepository, HouseGoalRepository houseGoalRepository,
                             HouseMemberRepository houseMemberRepository) {
        this.houseRepository = houseRepository;
        this.houseGoalRepository = houseGoalRepository;
        this.houseMemberRepository = houseMemberRepository;
    }

    // 집 상세 - ACTIVE 구성원만 조회 가능. 초대코드는 소유자에게만 내려간다.
    @Transactional(readOnly = true)
    public HouseDetailResponse getHouseDetail(Long userId, Long houseId) {
        House house = houseRepository.findById(houseId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_FOUND));
        HouseMember me = houseMemberRepository.findByHouseIdAndUserId(houseId, userId)
                .filter(HouseMember::isActive)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_MEMBER));
        // NOTE: house 를 응답에 써야 해서 requireActiveMember 로 합치지 않고 유지.

        List<GoalSummary> goals = houseGoalRepository.findByHouseIdWithGoal(houseId).stream()
                .map(HouseQueryService::toGoalSummary)
                .toList();
        return HouseDetailResponse.of(house, me.getRole(), goals);
    }

    // 구성원 목록 - ACTIVE 구성원만 조회 가능, ACTIVE 구성원만 노출(가입순).
    @Transactional(readOnly = true)
    public HouseMemberListResponse getMembers(Long userId, Long houseId) {
        requireActiveMember(userId, houseId);
        List<MemberSummary> items = houseMemberRepository
                .findByHouseIdAndStatusWithUser(houseId, HouseMemberStatus.ACTIVE).stream()
                .map(MemberSummary::of)
                .toList();
        return new HouseMemberListResponse(items);
    }

    // 집 존재(삭제 제외) + 내 ACTIVE membership 확인. 상세/구성원 목록 공용 guard.
    private HouseMember requireActiveMember(Long userId, Long houseId) {
        houseRepository.findById(houseId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_FOUND));
        return houseMemberRepository.findByHouseIdAndUserId(houseId, userId)
                .filter(HouseMember::isActive)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_MEMBER));
    }

    // 내가 속한(ACTIVE) 집 목록 - 먼저 가입한 집 먼저. 삭제된 집 제외.
    @Transactional(readOnly = true)
    public MyHouseListResponse getMyHouses(Long userId) {
        List<MyHouseSummary> items = houseMemberRepository
                .findByUserIdAndStatusWithHouse(userId, HouseMemberStatus.ACTIVE).stream()
                .map(MyHouseSummary::of)
                .toList();
        return new MyHouseListResponse(items);
    }

    @Transactional(readOnly = true)
    public HouseListResponse explore(int page, int size, String goalCode) {
        Pageable pageable = PageRequest.of(page, size);
        Page<House> houses = (goalCode == null || goalCode.isBlank())
                ? houseRepository.findExplorePage(pageable)
                : houseRepository.findExplorePageByGoalCode(goalCode, pageable);

        Map<Long, List<GoalSummary>> goalsByHouseId = loadGoals(houses.getContent());
        List<HouseSummary> items = houses.getContent().stream()
                .map(house -> HouseSummary.of(house, goalsByHouseId.getOrDefault(house.getId(), List.of())))
                .toList();
        return new HouseListResponse(items, page, size, houses.getTotalElements());
    }

    // 페이지의 goals 를 한 번에 조회해 N+1 을 피한다.
    private Map<Long, List<GoalSummary>> loadGoals(List<House> houses) {
        if (houses.isEmpty()) {
            return Map.of();
        }
        List<Long> houseIds = houses.stream().map(House::getId).toList();
        return houseGoalRepository.findByHouseIdInWithGoal(houseIds).stream()
                .collect(Collectors.groupingBy(
                        houseGoal -> houseGoal.getHouse().getId(),
                        Collectors.mapping(HouseQueryService::toGoalSummary, Collectors.toList())));
    }

    private static GoalSummary toGoalSummary(HouseGoal houseGoal) {
        return new GoalSummary(
                houseGoal.getGoal().getId(),
                houseGoal.getGoal().getCode(),
                houseGoal.getGoal().getName());
    }
}
