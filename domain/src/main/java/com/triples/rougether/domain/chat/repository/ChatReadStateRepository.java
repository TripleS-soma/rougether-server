package com.triples.rougether.domain.chat.repository;

import com.triples.rougether.domain.chat.entity.ChatReadState;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatReadStateRepository extends JpaRepository<ChatReadState, Long> {
    List<ChatReadState> findByRoomIdAndUserIdIn(Long roomId, Collection<Long> userIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ChatReadState s where s.roomId = :roomId and s.userId = :userId")
    Optional<ChatReadState> findWithLock(@Param("roomId") Long roomId, @Param("userId") Long userId);
}
