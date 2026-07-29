package com.triples.rougether.domain.house.repository;

import com.triples.rougether.domain.house.entity.HouseMissionDailyContribution;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HouseMissionDailyContributionRepository
        extends JpaRepository<HouseMissionDailyContribution, Long> {

    boolean existsByMissionIdAndMemberIdAndContributionDate(Long missionId, Long memberId, LocalDate date);

    // 자동 기여의 하루 1회 판정 전용 - 락 조회(current read)로 확인한다. REPEATABLE READ 의 일반
    // exists 는 트랜잭션 첫 SELECT 시점 스냅샷을 읽으므로, 루틴 완료 트랜잭션 도중 커밋된 직접 기여를
    // 놓치고 insert 의 UNIQUE 충돌(→완료까지 롤백)로 이어질 수 있다. 기여 insert 는 모두 미션 행 락
    // 아래서 일어나므로, 미션 락을 쥔 뒤의 이 조회에서 안 보이면 새로 생길 수도 없다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select c
            from HouseMissionDailyContribution c
            where c.mission.id = :missionId
              and c.member.id = :memberId
              and c.contributionDate = :date
            """)
    Optional<HouseMissionDailyContribution> findWithLockByMissionIdAndMemberIdAndContributionDate(
            @Param("missionId") Long missionId, @Param("memberId") Long memberId,
            @Param("date") LocalDate date);

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
