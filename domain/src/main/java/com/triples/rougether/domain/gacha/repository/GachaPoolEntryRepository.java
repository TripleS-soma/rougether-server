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
