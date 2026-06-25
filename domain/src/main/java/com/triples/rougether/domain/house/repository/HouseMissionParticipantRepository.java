package com.triples.rougether.domain.house.repository;

import com.triples.rougether.domain.house.entity.HouseMissionParticipant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HouseMissionParticipantRepository extends JpaRepository<HouseMissionParticipant, Long> {

    List<HouseMissionParticipant> findByMissionId(Long missionId);

    Optional<HouseMissionParticipant> findByMissionIdAndMemberId(Long missionId, Long memberId);
}
