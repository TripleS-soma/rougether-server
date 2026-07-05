package com.triples.rougether.domain.house.repository;

import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HouseMemberRepository extends JpaRepository<HouseMember, Long> {

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
}
