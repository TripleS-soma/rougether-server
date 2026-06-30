package com.triples.rougether.domain.room.repository;

import com.triples.rougether.domain.room.entity.RoomSurfaceSlot;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomSurfaceSlotRepository extends JpaRepository<RoomSurfaceSlot, Long> {

    List<RoomSurfaceSlot> findByRoomUserId(Long roomUserId);

    // 슬롯 + 배치된 user_item + item 을 fetch join(assetKey 조인, N+1 회피). 빈 슬롯 포함 위해 left join.
    @Query("""
            select s from RoomSurfaceSlot s
            left join fetch s.userItem ui
            left join fetch ui.item
            where s.room.userId = :roomUserId
            """)
    List<RoomSurfaceSlot> findByRoomUserIdWithItem(@Param("roomUserId") Long roomUserId);
}
