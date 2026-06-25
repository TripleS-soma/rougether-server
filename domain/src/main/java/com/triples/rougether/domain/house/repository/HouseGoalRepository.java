package com.triples.rougether.domain.house.repository;

import com.triples.rougether.domain.house.entity.HouseGoal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HouseGoalRepository extends JpaRepository<HouseGoal, Long> {

    List<HouseGoal> findByHouseId(Long houseId);
}
