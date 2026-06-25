package com.triples.rougether.domain.room.repository;

import com.triples.rougether.domain.room.entity.RoomSurfaceSlot;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomSurfaceSlotRepository extends JpaRepository<RoomSurfaceSlot, Long> {

    List<RoomSurfaceSlot> findByRoomUserId(Long roomUserId);
}
