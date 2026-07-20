package com.triples.rougether.domain.room.repository;

import com.triples.rougether.domain.room.entity.RoomItemPlacement;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomItemPlacementRepository extends JpaRepository<RoomItemPlacement, Long> {

    // 배치 + user_item + item 을 fetch join(assetKey 조인, N+1 회피). userItem 은 not null 이라 inner join.
    @Query("""
            select p from RoomItemPlacement p
            join fetch p.userItem ui
            join fetch ui.item
            where p.room.userId = :roomUserId
            order by p.zIndex asc, p.id asc
            """)
    List<RoomItemPlacement> findByRoomUserIdWithItem(@Param("roomUserId") Long roomUserId);

    // 전체 교체 저장의 선행 삭제. flushAutomatically 로 이 삭제가 이후 insert 보다 먼저 DB 에 반영되어
    // 같은 (room_user_id, user_item_id) 재배치가 unique 충돌 없이 통과한다.
    // clearAutomatically 는 쓰지 않는다 — 영속성 컨텍스트가 비워지면 잠금 조회한 PersonalRoom 의
    // 이후 변경(layout_format 전환·revision 증가)이 유실된다.
    @Modifying(flushAutomatically = true)
    @Query("delete from RoomItemPlacement p where p.room.userId = :roomUserId")
    void deleteByRoomUserId(@Param("roomUserId") Long roomUserId);
}
