package com.triples.rougether.domain.chat.repository;

import com.triples.rougether.domain.chat.entity.ChatRoom;
import com.triples.rougether.domain.chat.entity.ChatRoomType;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {
    @Query("select r.roomType as roomType, r.houseId as houseId from ChatRoom r where r.id = :id")
    Optional<AccessTarget> findAccessTarget(@Param("id") Long id);

    interface AccessTarget {
        ChatRoomType getRoomType();
        Long getHouseId();
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ChatRoom r where r.id = :id")
    Optional<ChatRoom> findWithLockById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ChatRoom r where r.houseId = :houseId")
    Optional<ChatRoom> findWithLockByHouseId(@Param("houseId") Long houseId);
}
