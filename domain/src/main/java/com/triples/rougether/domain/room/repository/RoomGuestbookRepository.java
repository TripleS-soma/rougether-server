package com.triples.rougether.domain.room.repository;

import com.triples.rougether.domain.room.entity.RoomGuestbook;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomGuestbookRepository extends JpaRepository<RoomGuestbook, Long> {

    List<RoomGuestbook> findByRoomOwnerIdAndDeletedAtIsNull(Long roomOwnerId);

    // 커서 기반 최신순 조회 - cursor(null 이면 첫 페이지)보다 오래된 글을 id 내림차순으로.
    // author 는 응답에 닉네임이 필요해 fetch join (N+1 회피).
    @Query("select g from RoomGuestbook g join fetch g.author "
            + "where g.roomOwner.id = :roomOwnerId and g.house.id = :houseId and g.deletedAt is null "
            + "and (:cursor is null or g.id < :cursor) order by g.id desc")
    List<RoomGuestbook> findPageByCursor(@Param("roomOwnerId") Long roomOwnerId,
                                         @Param("houseId") Long houseId,
                                         @Param("cursor") Long cursor,
                                         Pageable pageable);
}
