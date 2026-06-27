package com.triples.rougether.domain.house.repository;

import com.triples.rougether.domain.house.entity.HouseMember;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HouseMemberRepository extends JpaRepository<HouseMember, Long> {

    List<HouseMember> findByHouseIdAndStatus(Long houseId, String status);

    List<HouseMember> findByUserIdAndStatus(Long userId, String status);

    Optional<HouseMember> findByHouseIdAndUserId(Long houseId, Long userId);
}
