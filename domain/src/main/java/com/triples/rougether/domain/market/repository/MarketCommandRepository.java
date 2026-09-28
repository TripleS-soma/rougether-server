package com.triples.rougether.domain.market.repository;

import com.triples.rougether.domain.market.entity.MarketCommand;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketCommandRepository extends JpaRepository<MarketCommand, Long> {
}
