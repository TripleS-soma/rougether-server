package com.triples.rougether.domain.house.repository;

import com.triples.rougether.domain.house.entity.HouseMissionDailyReward;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HouseMissionDailyRewardRepository extends JpaRepository<HouseMissionDailyReward, Long> {

    boolean existsByMissionIdAndRewardDate(Long missionId, LocalDate rewardDate);

    // 목록 화면용 일괄 조회 (N+1 회피): 해당 날짜에 보상이 지급된 missionId 목록.
    @Query("select r.mission.id from HouseMissionDailyReward r "
            + "where r.mission.id in :missionIds and r.rewardDate = :date")
    List<Long> findClaimedMissionIds(@Param("missionIds") Collection<Long> missionIds,
                                     @Param("date") LocalDate date);
}
