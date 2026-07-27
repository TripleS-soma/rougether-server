package com.triples.rougether.domain.house.repository;

import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HouseMemberRepository extends JpaRepository<HouseMember, Long> {

    long countByHouseIdAndStatus(Long houseId, HouseMemberStatus status);

    // 집 구성원 목록: 지정 상태(ACTIVE)만, 가입순. user fetch join(N+1 회피).
    @Query("select hm from HouseMember hm join fetch hm.user "
            + "where hm.house.id = :houseId and hm.status = :status "
            + "order by hm.joinedAt asc, hm.id asc")
    List<HouseMember> findByHouseIdAndStatusWithUser(@Param("houseId") Long houseId,
                                                     @Param("status") HouseMemberStatus status);

    // 내 집 목록: ACTIVE membership + 삭제 안 된 집, 먼저 가입한 순. house fetch join(N+1 회피).
    @Query("select hm from HouseMember hm join fetch hm.house h "
            + "where hm.user.id = :userId and hm.status = :status and h.deletedAt is null "
            + "order by hm.joinedAt asc, hm.id asc")
    List<HouseMember> findByUserIdAndStatusWithHouse(@Param("userId") Long userId,
                                                     @Param("status") HouseMemberStatus status);

    List<HouseMember> findByHouseIdAndStatus(Long houseId, String status);

    List<HouseMember> findByUserIdAndStatus(Long userId, String status);

    Optional<HouseMember> findByHouseIdAndUserId(Long houseId, Long userId);

    // house 행 락을 잡은 뒤 내 멤버십을 다시 읽을 때 쓴다. 일반 조회면 REPEATABLE READ 스냅샷을 봐서
    // "락 대기 중에 다른 트랜잭션이 나를 소유자로 승격시킨 것"을 놓친다(동시 탈퇴 시 소유자 없는 집이 생김).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select hm from HouseMember hm where hm.house.id = :houseId and hm.user.id = :userId")
    Optional<HouseMember> findForUpdateByHouseIdAndUserId(@Param("houseId") Long houseId,
                                                          @Param("userId") Long userId);

    // 회원 탈퇴 시 자동 양도 대상 선정 — 탈퇴자를 뺀 ACTIVE 구성원 중 가장 먼저 가입한 사람.
    // 탈퇴한 회원은 leave 로 status 가 LEFT 가 되므로 ACTIVE 필터만으로 후보에서 빠진다.
    // users 를 조인해 deleted_at 을 함께 보면 FOR UPDATE 가 상대방 user 행까지 잠가,
    // 서로의 집에 함께 속한 두 사람이 동시에 탈퇴할 때 데드락이 난다(실제로 재현됨).
    // joined_at 동률이면 id 오름차순으로 결정적으로 뽑는다(다른 목록 조회와 같은 정렬 규칙).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select hm from HouseMember hm "
            + "where hm.house.id = :houseId and hm.status = :status and hm.user.id <> :excludeUserId "
            + "order by hm.joinedAt asc, hm.id asc")
    List<HouseMember> findTransferCandidates(@Param("houseId") Long houseId,
                                             @Param("status") HouseMemberStatus status,
                                             @Param("excludeUserId") Long excludeUserId);
}
