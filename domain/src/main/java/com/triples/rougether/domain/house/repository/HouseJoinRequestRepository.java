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

    // 참여/신청 판정용 - 락 조회(current read). house 락 이전에 REPEATABLE READ 스냅샷이 잡힌
    // 트랜잭션(개인 초대코드 참여)에서도 락 대기 중 커밋된 신청을 보고 중복 신청 500 을 막는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from HouseJoinRequest request "
            + "where request.house.id = :houseId and request.user.id = :userId")
    Optional<HouseJoinRequest> findWithLockByHouseIdAndUserId(@Param("houseId") Long houseId,
                                                              @Param("userId") Long userId);

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

    // 신청자 본인 철회용 - 소유자의 동시 accept/reject 와 직렬화하기 위한 락 조회(current read).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from HouseJoinRequest request where request.id = :requestId")
    Optional<HouseJoinRequest> findWithLockById(@Param("requestId") Long requestId);

    // 내가 보낸 신청 목록용 - 카드 요약을 위해 house 를 함께 로드, 삭제된 집 제외.
    @Query("select request from HouseJoinRequest request join fetch request.house h "
            + "where request.user.id = :userId and request.status = :status "
            + "and h.deletedAt is null "
            + "order by request.requestedAt desc, request.id desc")
    List<HouseJoinRequest> findByUserIdAndStatusWithHouse(@Param("userId") Long userId,
                                                          @Param("status") HouseJoinRequestStatus status);
}
