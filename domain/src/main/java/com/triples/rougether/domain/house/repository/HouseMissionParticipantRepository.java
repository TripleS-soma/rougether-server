package com.triples.rougether.domain.house.repository;

import com.triples.rougether.domain.house.entity.HouseMissionParticipant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HouseMissionParticipantRepository extends JpaRepository<HouseMissionParticipant, Long> {

    List<HouseMissionParticipant> findByMissionId(Long missionId);

    Optional<HouseMissionParticipant> findByMissionIdAndMemberId(Long missionId, Long memberId);

    // 미션 진행률(기여 합산).
    @Query("select coalesce(sum(p.contributionValue), 0) from HouseMissionParticipant p where p.mission.id = :missionId")
    long sumContributionByMissionId(@Param("missionId") Long missionId);

    // 목록 화면용 일괄 합산 (N+1 회피): [missionId, sum] 행.
    @Query("select p.mission.id, coalesce(sum(p.contributionValue), 0) from HouseMissionParticipant p "
            + "where p.mission.id in :missionIds group by p.mission.id")
    List<Object[]> sumContributionByMissionIds(@Param("missionIds") java.util.Collection<Long> missionIds);

    // 목록 화면용 내 누적 기여 (N+1 회피): [missionId, contributionValue] 행. (mission, member) 참여 행은 최대 1개.
    @Query("select p.mission.id, p.contributionValue from HouseMissionParticipant p "
            + "where p.member.id = :memberId and p.mission.id in :missionIds")
    List<Object[]> findContributionByMemberIdAndMissionIds(@Param("memberId") Long memberId,
                                                            @Param("missionIds") java.util.Collection<Long> missionIds);
}
