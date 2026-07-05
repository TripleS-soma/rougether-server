package com.triples.rougether.domain.house.repository;

import com.triples.rougether.domain.house.entity.HouseGoal;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HouseGoalRepository extends JpaRepository<HouseGoal, Long> {

    List<HouseGoal> findByHouseId(Long houseId);

    // 집 상세의 goals 조회 (goal fetch join).
    @Query("select hg from HouseGoal hg join fetch hg.goal where hg.house.id = :houseId")
    List<HouseGoal> findByHouseIdWithGoal(@Param("houseId") Long houseId);

    // 탐색 목록의 goals 일괄 조회 (N+1 회피).
    @Query("select hg from HouseGoal hg join fetch hg.goal where hg.house.id in :houseIds")
    List<HouseGoal> findByHouseIdInWithGoal(@Param("houseIds") Collection<Long> houseIds);
}
