package com.triples.rougether.domain.room.repository;

import com.triples.rougether.domain.room.entity.RoomSurfaceSlot;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomSurfaceSlotRepository extends JpaRepository<RoomSurfaceSlot, Long> {

    List<RoomSurfaceSlot> findByRoomUserId(Long roomUserId);

    Optional<RoomSurfaceSlot> findByRoomUserIdAndSlotType(Long roomUserId, String slotType);

    // 슬롯 + 배치된 user_item + item 을 fetch join(assetKey 조인, N+1 회피). 빈 슬롯 포함 위해 left join.
    @Query("""
            select s from RoomSurfaceSlot s
            left join fetch s.userItem ui
            left join fetch ui.item
            where s.room.userId = :roomUserId
            """)
    List<RoomSurfaceSlot> findByRoomUserIdWithItem(@Param("roomUserId") Long roomUserId);

    // 여러 방의 슬롯을 한 번에 조회(집 미리보기 memberRooms 등 배치 렌더용) - 방별 반복 조회(N+1) 회피.
    @Query("""
            select s from RoomSurfaceSlot s
            left join fetch s.userItem ui
            left join fetch ui.item
            where s.room.userId in :roomUserIds
            """)
    List<RoomSurfaceSlot> findByRoomUserIdInWithItem(@Param("roomUserIds") Collection<Long> roomUserIds);
}
