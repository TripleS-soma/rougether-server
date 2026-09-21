package com.triples.rougether.domain.chat.repository;

import com.triples.rougether.domain.chat.entity.ChatMessage;
import java.util.List;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ChatMessage> findByRoomIdAndSenderIdAndClientMessageId(Long roomId, Long senderId, String clientMessageId);

    @Query("select m from ChatMessage m join fetch m.sender where m.roomId = :roomId "
            + "and (:before is null or m.sequence < :before) order by m.sequence desc")
    List<ChatMessage> findBefore(@Param("roomId") Long roomId, @Param("before") Long before, Pageable page);

    @Query("select m from ChatMessage m join fetch m.sender where m.roomId = :roomId "
            + "and m.sequence > :after order by m.sequence asc")
    List<ChatMessage> findAfter(@Param("roomId") Long roomId, @Param("after") long after, Pageable page);
}
