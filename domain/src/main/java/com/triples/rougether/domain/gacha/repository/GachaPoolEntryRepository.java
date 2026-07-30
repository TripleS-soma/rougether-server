package com.triples.rougether.domain.gacha.repository;

import com.triples.rougether.domain.gacha.entity.GachaPoolEntry;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GachaPoolEntryRepository extends JpaRepository<GachaPoolEntry, Long> {

    List<GachaPoolEntry> findByGachaIdAndActiveIsTrue(Long gachaId);

    // 사용자 보상 미리보기용. 활성 엔트리만 ID 순으로 반환하고 보상 참조를 한 번에 조회함.
    @Query("""
            select entry
            from GachaPoolEntry entry
            left join fetch entry.item
            left join fetch entry.character
            where entry.gacha.id = :gachaId
              and entry.active = true
            order by entry.id asc
            """)
    List<GachaPoolEntry> findActiveRewardsByGachaId(@Param("gachaId") Long gachaId);

    // 비활성 머신의 잔존 엔트리는 등록/등급 관리 대상이 아니므로 머신 활성까지 함께 검사함.
    @Query("""
            select entry
            from GachaPoolEntry entry
            join fetch entry.item item
            where entry.rewardType = com.triples.rougether.domain.gacha.entity.RewardType.ITEM
              and entry.active = true
              and entry.gacha.active = true
              and item.id in :itemIds
            """)
    List<GachaPoolEntry> findActiveItemEntriesByItemIds(@Param("itemIds") Collection<Long> itemIds);

    // 뽑기 풀 등록의 락 획득 후 재확인 전용 — REPEATABLE READ 스냅샷 대신 최신 커밋을 읽도록 locking read 로 조회한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select entry
            from GachaPoolEntry entry
            where entry.rewardType = com.triples.rougether.domain.gacha.entity.RewardType.ITEM
              and entry.active = true
              and entry.gacha.active = true
              and entry.item.id = :itemId
            """)
    List<GachaPoolEntry> findActiveItemEntriesForUpdate(@Param("itemId") Long itemId);
}
