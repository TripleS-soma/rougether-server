package com.triples.rougether.domain.chat.repository;

import com.triples.rougether.domain.chat.entity.ChatImage;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatImageRepository extends JpaRepository<ChatImage, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ChatImage i where i.id = :id")
    Optional<ChatImage> findForUpdate(@Param("id") Long id);

    // 메시지 하나에 사진이 여러 장일 수 있음. 메시지별로 보낸 순서대로 돌려줌.
    @Query("select i from ChatImage i join fetch i.owner where i.messageId in :messageIds "
            + "order by i.messageId, i.sortOrder, i.id")
    List<ChatImage> findWithOwnerByMessageIdIn(@Param("messageIds") Collection<Long> messageIds);

    long countByMessageId(Long messageId);

    long countByOwnerIdAndMessageIdIsNullAndExpiresAtAfter(Long ownerId, Instant now);

    long countByOwnerIdAndCreatedAtGreaterThanEqual(Long ownerId, Instant since);

    // 만료된 미전송 업로드, 운영자가 삭제한 메시지, 탈퇴한 사람, 해체된 집의 사진이 정리 대상임.
    // 오늘 올린 미전송 행은 다음 날까지 남겨 둠. 취소 후 정리로 하루 업로드 한도가 풀리지 않게 하기 위함.
    @Query("select i.id from ChatImage i join i.owner o join ChatRoom r on r.id = i.roomId "
            + "left join ChatMessage m on m.id = i.messageId left join House h on h.id = r.houseId "
            + "where i.id > :after and ((i.messageId is null and i.expiresAt <= :now and i.createdAt < :startOfToday) "
            + "or (i.ready = true and (m.deletedAt is not null or o.deletedAt is not null or h.deletedAt is not null))) "
            + "order by i.id")
    List<Long> findCleanupCandidates(@Param("after") long after, @Param("now") Instant now,
                                     @Param("startOfToday") Instant startOfToday, Pageable page);
}
