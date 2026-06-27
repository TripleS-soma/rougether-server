package com.triples.rougether.domain.room.repository;

import com.triples.rougether.domain.room.entity.RoomGuestbook;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomGuestbookRepository extends JpaRepository<RoomGuestbook, Long> {

    List<RoomGuestbook> findByRoomOwnerIdAndDeletedAtIsNull(Long roomOwnerId);
}
