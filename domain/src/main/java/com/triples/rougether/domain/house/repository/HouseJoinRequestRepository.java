package com.triples.rougether.domain.house.repository;

import com.triples.rougether.domain.house.entity.HouseJoinRequest;
import com.triples.rougether.domain.house.entity.HouseJoinRequestStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HouseJoinRequestRepository extends JpaRepository<HouseJoinRequest, Long> {

    Optional<HouseJoinRequest> findByHouseIdAndUserId(Long houseId, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from HouseJoinRequest request join fetch request.user "
            + "where request.id = :requestId and request.house.id = :houseId")
    Optional<HouseJoinRequest> findWithLockByIdAndHouseId(@Param("requestId") Long requestId,
                                                         @Param("houseId") Long houseId);

    @Query("select request from HouseJoinRequest request join fetch request.user "
            + "where request.house.id = :houseId and request.status = :status "
            + "order by request.requestedAt asc, request.id asc")
    List<HouseJoinRequest> findByHouseIdAndStatusWithUser(
            @Param("houseId") Long houseId,
            @Param("status") HouseJoinRequestStatus status);

    @Query("select request from HouseJoinRequest request "
            + "where request.house.id in :houseIds and request.user.id = :userId")
    List<HouseJoinRequest> findByHouseIdInAndUserId(@Param("houseIds") Collection<Long> houseIds,
                                                   @Param("userId") Long userId);

    // 회원탈퇴 정리 전용 - 탈퇴자의 대기 중 신청 전량 조회(철회는 엔티티 reject 로 처리).
    List<HouseJoinRequest> findAllByUserIdAndStatus(Long userId, HouseJoinRequestStatus status);
}
