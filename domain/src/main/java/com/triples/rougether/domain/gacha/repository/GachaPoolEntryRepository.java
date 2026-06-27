package com.triples.rougether.domain.gacha.repository;

import com.triples.rougether.domain.gacha.entity.GachaPoolEntry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GachaPoolEntryRepository extends JpaRepository<GachaPoolEntry, Long> {

    List<GachaPoolEntry> findByGachaIdAndActiveIsTrue(Long gachaId);
}
