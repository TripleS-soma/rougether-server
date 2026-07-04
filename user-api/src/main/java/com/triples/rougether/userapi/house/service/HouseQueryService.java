package com.triples.rougether.userapi.house.service;

import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseGoal;
import com.triples.rougether.domain.house.repository.HouseGoalRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.userapi.house.dto.HouseListResponse;
import com.triples.rougether.userapi.house.dto.HouseListResponse.GoalSummary;
import com.triples.rougether.userapi.house.dto.HouseListResponse.HouseSummary;
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

    public HouseQueryService(HouseRepository houseRepository, HouseGoalRepository houseGoalRepository) {
        this.houseRepository = houseRepository;
        this.houseGoalRepository = houseGoalRepository;
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
