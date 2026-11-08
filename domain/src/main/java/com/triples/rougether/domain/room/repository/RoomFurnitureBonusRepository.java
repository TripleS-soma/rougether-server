package com.triples.rougether.domain.room.repository;

import com.triples.rougether.domain.room.entity.PersonalRoom;
import java.util.Collection;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// 배치 정본에 실제 놓인 활성 보유 가구의 종류만 셈. 슬롯에서 자유배치로 전환한 옛 행은 제외함.
public interface RoomFurnitureBonusRepository extends Repository<PersonalRoom, Long> {

    @Query("""
            select count(distinct i.id) from RoomItemPlacement p
            join p.room r join p.userItem ui join ui.item i join i.theme t
            where r.userId = :userId
              and r.layoutFormat = com.triples.rougether.domain.room.entity.RoomLayoutFormat.FREE_V1
              and ui.user.id = :userId and ui.deletedAt is null
              and t.code = :themeCode and i.placementType = 'positioned' and i.categoryCode = 'furniture'
            """)
    long countFreeLayoutFurnitureKinds(@Param("userId") Long userId, @Param("themeCode") String themeCode);

    @Query("""
            select count(distinct i.id) from RoomSurfaceSlot s
            join s.room r join s.userItem ui join ui.item i join i.theme t
            where r.userId = :userId
              and r.layoutFormat = com.triples.rougether.domain.room.entity.RoomLayoutFormat.SLOT_V1
              and s.slotType in :slotTypes
              and ui.user.id = :userId and ui.deletedAt is null
              and t.code = :themeCode and i.placementType = 'positioned' and i.categoryCode = 'furniture'
            """)
    long countSlotLayoutFurnitureKinds(@Param("userId") Long userId, @Param("themeCode") String themeCode,
                                      @Param("slotTypes") Collection<String> slotTypes);
}
