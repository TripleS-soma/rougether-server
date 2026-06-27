package com.triples.rougether.domain.house.repository;

import com.triples.rougether.domain.house.entity.HouseMission;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HouseMissionRepository extends JpaRepository<HouseMission, Long> {

    List<HouseMission> findByHouseIdAndStatus(Long houseId, String status);
}
