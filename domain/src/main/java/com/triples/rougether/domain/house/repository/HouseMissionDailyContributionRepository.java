package com.triples.rougether.domain.house.repository;

import com.triples.rougether.domain.house.entity.HouseMissionDailyContribution;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HouseMissionDailyContributionRepository
        extends JpaRepository<HouseMissionDailyContribution, Long> {

    boolean existsByMissionIdAndMemberIdAndContributionDate(Long missionId, Long memberId, LocalDate date);

    // DAILY 판정: 해당 날짜에 기여한 멤버 수.
    long countByMissionIdAndContributionDate(Long missionId, LocalDate date);

    // 목록 화면용 일괄 카운트 (N+1 회피): [missionId, count] 행.
    @Query("select c.mission.id, count(c) from HouseMissionDailyContribution c "
            + "where c.mission.id in :missionIds and c.contributionDate = :date group by c.mission.id")
    List<Object[]> countByMissionIdsAndDate(@Param("missionIds") Collection<Long> missionIds,
                                            @Param("date") LocalDate date);
}
