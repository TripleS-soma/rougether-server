package com.triples.rougether.domain.chat.repository;

import com.triples.rougether.domain.chat.entity.ChatMessageReaction;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageReactionRepository extends JpaRepository<ChatMessageReaction, Long> {
    Optional<ChatMessageReaction> findByMessageIdAndUserId(Long messageId, Long userId);

    // 탈퇴한 회원의 공감은 응답에서 뺌. 누른 순서로 돌려줌.
    @Query("select r from ChatMessageReaction r join User u on u.id = r.userId "
            + "where r.messageId in :messageIds and u.deletedAt is null order by r.createdAt, r.id")
    List<ChatMessageReaction> findVisibleByMessageIdIn(@Param("messageIds") Collection<Long> messageIds);
}
