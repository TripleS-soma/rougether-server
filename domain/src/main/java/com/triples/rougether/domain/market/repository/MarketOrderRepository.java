package com.triples.rougether.domain.market.repository;

import com.triples.rougether.domain.market.entity.MarketOrder;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MarketOrderRepository extends JpaRepository<MarketOrder, Long> {

    Optional<MarketOrder> findByIdAndUserId(Long id, Long userId);

    // 1인 1개 판정: 미체결 매수와 미체결 보유분 매도(맡긴 가구)를 보유로 셈.
    @Query("select count(o) > 0 from MarketOrder o where o.userId = :userId and o.assetId = :assetId "
            + "and o.status = com.triples.rougether.domain.market.entity.OrderStatus.OPEN "
            + "and (o.side = com.triples.rougether.domain.market.entity.OrderSide.BUY "
            + "or o.source = com.triples.rougether.domain.market.entity.OrderSource.INVENTORY)")
    boolean existsOpenHolding(@Param("userId") Long userId, @Param("assetId") Long assetId);
}
