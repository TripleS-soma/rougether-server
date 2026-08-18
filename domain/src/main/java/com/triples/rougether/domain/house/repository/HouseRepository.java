package com.triples.rougether.domain.house.repository;

import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HouseRepository extends JpaRepository<House, Long> {

    // 탐색 노출 조건(#308): ACTIVE 멤버가 봇뿐인 집(사람 0명)은 숨긴다. 멤버가 없는 집은 그대로 노출.
    // 네 개의 explore 쿼리가 같은 술어를 공유하도록 상수로 둔다(복붙 드리프트 방지).
    String HAS_HUMAN_OR_NO_BOT_MEMBER =
            "and (not exists (select 1 from HouseMember hb where hb.house = h and hb.user.bot = true "
            + "  and hb.status = com.triples.rougether.domain.house.entity.HouseMemberStatus.ACTIVE) "
            + "  or exists (select 1 from HouseMember hu where hu.house = h and hu.user.bot = false "
            + "  and hu.status = com.triples.rougether.domain.house.entity.HouseMemberStatus.ACTIVE)) ";

    Optional<House> findByInviteCode(String inviteCode);

    // 참여(정원 검사 + 구성원 수 증가) 경로 전용 - 행 락으로 동시 참여의 정원 초과를 막는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from House h where h.inviteCode = :inviteCode")
    Optional<House> findWithLockByInviteCode(@Param("inviteCode") String inviteCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from House h where h.id = :houseId")
    Optional<House> findWithLockById(@Param("houseId") Long houseId);

    // 탐색 목록: 삭제 안 된 집, 최신 생성순. ACTIVE 멤버가 봇뿐인 집(사람 0명)은 노출하지 않는다(#308 —
    // 봇만 남은 집이 추천에 뜨지 않게). 아래 goalCode·excludeJoined 변형도 같은 조건을 공유한다.
    @Query("select h from House h where h.deletedAt is null "
            + HAS_HUMAN_OR_NO_BOT_MEMBER
            + "order by h.createdAt desc, h.id desc")
    Page<House> findExplorePage(Pageable pageable);

    // goalCode 필터 - house_goals 를 서브쿼리로 걸어 중복 행 없이 페이징한다.
    @Query("select h from House h where h.deletedAt is null "
            + HAS_HUMAN_OR_NO_BOT_MEMBER
            + "and h.id in (select hg.house.id from HouseGoal hg where hg.goal.code = :goalCode) "
            + "order by h.createdAt desc, h.id desc")
    Page<House> findExplorePageByGoalCode(@Param("goalCode") String goalCode, Pageable pageable);

    // excludeJoined 필터 - 내가 지금 가입해 있는(ACTIVE) 집만 제외한다. LEFT/KICKED 이력 집은 목록에 남는다.
    // NOT IN 대신 NOT EXISTS - 가입 집이 많아져도 페이지 스캔마다 서브쿼리 결과를 다시 만들지 않는다.
    @Query("select h from House h where h.deletedAt is null "
            + HAS_HUMAN_OR_NO_BOT_MEMBER
            + "and not exists (select 1 from HouseMember hm "
            + "where hm.house = h and hm.user.id = :userId and hm.status = :status) "
            + "order by h.createdAt desc, h.id desc")
    Page<House> findExplorePageExcludingMemberStatus(@Param("userId") Long userId,
                                                     @Param("status") HouseMemberStatus status,
                                                     Pageable pageable);

    @Query("select h from House h where h.deletedAt is null "
            + HAS_HUMAN_OR_NO_BOT_MEMBER
            + "and h.id in (select hg.house.id from HouseGoal hg where hg.goal.code = :goalCode) "
            + "and not exists (select 1 from HouseMember hm "
            + "where hm.house = h and hm.user.id = :userId and hm.status = :status) "
            + "order by h.createdAt desc, h.id desc")
    Page<House> findExplorePageByGoalCodeExcludingMemberStatus(@Param("goalCode") String goalCode,
                                                               @Param("userId") Long userId,
                                                               @Param("status") HouseMemberStatus status,
                                                               Pageable pageable);

    boolean existsByInviteCode(String inviteCode);
}
