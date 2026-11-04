package com.triples.rougether.domain.market.repository;

import com.triples.rougether.domain.market.entity.MarketPurchase;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketPurchaseRepository extends JpaRepository<MarketPurchase, Long> {
    Optional<MarketPurchase> findByBuyerUserIdAndRequestId(Long buyerUserId, String requestId);
    boolean existsByBuyerUserIdAndAssetId(Long buyerUserId, Long assetId);
}
