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

    // DAILY 판정: 해당 날짜에 기여한 "현재 ACTIVE" 멤버 수.
    // 분모(house.current_member_count)가 ACTIVE 기준이므로 분자도 맞춘다 — 기여 후 탈퇴/강퇴한 멤버가
    // 남아 있으면 달성률이 100% 를 넘거나 남은 인원 없이 달성되는 왜곡이 생긴다(#202 리뷰).
    @Query("""
            select count(c)
            from HouseMissionDailyContribution c
            where c.mission.id = :missionId
              and c.contributionDate = :date
              and c.member.status = com.triples.rougether.domain.house.entity.HouseMemberStatus.ACTIVE
            """)
    long countActiveByMissionIdAndContributionDate(@Param("missionId") Long missionId,
                                                   @Param("date") LocalDate date);

    // 목록 화면용 일괄 카운트 (N+1 회피): [missionId, count] 행. 위와 같은 이유로 ACTIVE 멤버만 센다.
    @Query("""
            select c.mission.id, count(c)
            from HouseMissionDailyContribution c
            where c.mission.id in :missionIds
              and c.contributionDate = :date
              and c.member.status = com.triples.rougether.domain.house.entity.HouseMemberStatus.ACTIVE
            group by c.mission.id
            """)
    List<Object[]> countActiveByMissionIdsAndDate(@Param("missionIds") Collection<Long> missionIds,
                                                  @Param("date") LocalDate date);
}
