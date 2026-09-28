package com.triples.rougether.domain.market.repository;

import com.triples.rougether.domain.market.entity.MarketOrder;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketOrderRepository extends JpaRepository<MarketOrder, Long> {
}
