package com.triples.rougether.domain.room.repository;

import com.triples.rougether.domain.room.entity.RoomCobweb;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomCobwebRepository extends JpaRepository<RoomCobweb, Long> {

    // 한 번 발생한 거미줄은 주인이 복귀해도 직접 청소할 수 있도록 청소 전까지 노출한다.
    @Query("select c from RoomCobweb c where c.roomUserId = :roomUserId and c.cleanedAt is null")
    Optional<RoomCobweb> findVisibleActive(@Param("roomUserId") Long roomUserId);

    @Query("select c from RoomCobweb c where c.roomUserId in :roomUserIds and c.cleanedAt is null")
    List<RoomCobweb> findVisibleActiveByRoomUserIdIn(
            @Param("roomUserIds") Collection<Long> roomUserIds);

    // 청소·보상 동시 요청을 방 단위로 직렬화한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from RoomCobweb c where c.roomUserId = :roomUserId and c.cleanedAt is null")
    Optional<RoomCobweb> findActiveForUpdate(@Param("roomUserId") Long roomUserId);
}
