package com.triples.rougether.userapi.house.service;

import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseGoal;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.repository.HouseGoalRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.userapi.house.dto.HouseListResponse;
import com.triples.rougether.userapi.house.dto.HouseListResponse.GoalSummary;
import com.triples.rougether.userapi.house.dto.HouseListResponse.HouseSummary;
import com.triples.rougether.userapi.house.dto.MyHouseListResponse;
import com.triples.rougether.userapi.house.dto.MyHouseListResponse.MyHouseSummary;
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
