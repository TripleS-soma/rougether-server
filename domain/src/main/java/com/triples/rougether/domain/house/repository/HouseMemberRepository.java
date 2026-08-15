package com.triples.rougether.domain.house.repository;

import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.support.UserMetricCount;
import jakarta.persistence.LockModeType;
import java.util.Collection;
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

    boolean existsByInviteCode(String inviteCode);

    // 구성원 개인 초대코드 미리보기 전용 - 초대자와 집을 함께 확정한다. house fetch join(N+1 회피).
    // 참여 경로에서는 쓰지 않는다 - 멤버 엔티티가 PC 에 실리면 이후 락 재조회가 1차 캐시에 가려진다.
    @Query("select hm from HouseMember hm join fetch hm.house where hm.inviteCode = :inviteCode")
    Optional<HouseMember> findByInviteCodeWithHouse(@Param("inviteCode") String inviteCode);

    // 구성원 개인 초대코드 참여 전용 - 초대자 위치(house_id, user_id)만 스칼라로 스냅샷함.
    // 멤버 엔티티를 PC 에 싣지 않아야 house 락 이후의 락 재조회가 1차 캐시에 가려지지 않고
    // 동시 탈퇴·강퇴·코드 회전의 선행 커밋을 반영한 최신 상태를 읽는다(회원탈퇴 정리 패턴과 동일).
    @Query("select hm.house.id as houseId, hm.user.id as userId from HouseMember hm join hm.house h "
            + "where hm.inviteCode = :inviteCode and h.deletedAt is null")
    Optional<InviteJoinTarget> findJoinTargetByInviteCode(@Param("inviteCode") String inviteCode);

    interface InviteJoinTarget {
        Long getHouseId();

        Long getUserId();
    }

    // 자동 기여·개인 초대코드 참여 경로용 - 락 조회(current read). REPEATABLE READ 스냅샷이 잡힌 뒤
    // 커밋된 탈퇴·강퇴를 일반 조회는 못 보므로, 강퇴 직후 기여·무효 코드 참여가 통과하는 경합 창을 닫는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select hm from HouseMember hm where hm.house.id = :houseId and hm.user.id = :userId")
    Optional<HouseMember> findWithLockByHouseIdAndUserId(@Param("houseId") Long houseId,
                                                         @Param("userId") Long userId);

    // 회원탈퇴 정리 전용 - 정리 대상 집 id 만 스칼라로 스냅샷함.
    // 멤버 엔티티를 PC 에 싣지 않아야 이후 락 조회가 1차 캐시에 가려지지 않고 최신 상태를 읽는다.
    @Query("select hm.house.id from HouseMember hm where hm.user.id = :userId and hm.status = :status")
    List<Long> findHouseIdsByUserIdAndStatus(@Param("userId") Long userId,
                                             @Param("status") HouseMemberStatus status);

    // 회원탈퇴 정리 전용 - 락 조회(current read)로 동시 강퇴·가입의 선행 커밋을 반영한 활성 멤버를
    // 가입순(동률 시 id 오름차순)으로 읽는다. 소유권 승계의 최선임 판정 순서와 동일.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select hm from HouseMember hm where hm.house.id = :houseId and hm.status = :status "
            + "order by hm.joinedAt asc, hm.id asc")
    List<HouseMember> findAllWithLockByHouseIdAndStatus(@Param("houseId") Long houseId,
                                                        @Param("status") HouseMemberStatus status);

    @Query("select hm.user.id as userId, count(hm.id) as metricCount from HouseMember hm "
            + "join hm.house h where hm.user.id in :userIds and hm.status = :status "
            + "and h.deletedAt is null group by hm.user.id")
    List<UserMetricCount> countActiveByUserIds(@Param("userIds") Collection<Long> userIds,
                                               @Param("status") HouseMemberStatus status);
}
