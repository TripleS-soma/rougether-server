package com.triples.rougether.domain.gacha.repository;

import com.triples.rougether.domain.gacha.entity.GachaPoolEntry;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GachaPoolEntryRepository extends JpaRepository<GachaPoolEntry, Long> {

    List<GachaPoolEntry> findByGachaIdAndActiveIsTrue(Long gachaId);

    @Query("""
            select entry
            from GachaPoolEntry entry
            join fetch entry.item item
            where entry.rewardType = com.triples.rougether.domain.gacha.entity.RewardType.ITEM
              and entry.active = true
              and item.id in :itemIds
            """)
    List<GachaPoolEntry> findActiveItemEntriesByItemIds(@Param("itemIds") Collection<Long> itemIds);
}
