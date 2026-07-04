package com.triples.rougether.domain.house.repository;

import com.triples.rougether.domain.house.entity.House;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HouseRepository extends JpaRepository<House, Long> {

    Optional<House> findByInviteCode(String inviteCode);

    // 참여(정원 검사 + 구성원 수 증가) 경로 전용 - 행 락으로 동시 참여의 정원 초과를 막는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from House h where h.inviteCode = :inviteCode")
    Optional<House> findWithLockByInviteCode(@Param("inviteCode") String inviteCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from House h where h.id = :houseId")
    Optional<House> findWithLockById(@Param("houseId") Long houseId);

    // 탐색 목록: 삭제 안 된 집, 최신 생성순.
    @Query("select h from House h where h.deletedAt is null order by h.createdAt desc, h.id desc")
    Page<House> findExplorePage(Pageable pageable);

    // goalCode 필터 - house_goals 를 서브쿼리로 걸어 중복 행 없이 페이징한다.
    @Query("select h from House h where h.deletedAt is null "
            + "and h.id in (select hg.house.id from HouseGoal hg where hg.goal.code = :goalCode) "
            + "order by h.createdAt desc, h.id desc")
    Page<House> findExplorePageByGoalCode(@Param("goalCode") String goalCode, Pageable pageable);

    boolean existsByInviteCode(String inviteCode);
}
