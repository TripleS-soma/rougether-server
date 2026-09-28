package com.triples.rougether.domain.market.repository;

import com.triples.rougether.domain.market.entity.MarketAsset;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketAssetRepository extends JpaRepository<MarketAsset, Long> {

    boolean existsByItemId(Long itemId);
}
