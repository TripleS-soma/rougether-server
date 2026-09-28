package com.triples.rougether.domain.market.repository;

import com.triples.rougether.domain.market.entity.MarketTrade;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketTradeRepository extends JpaRepository<MarketTrade, Long> {
}
