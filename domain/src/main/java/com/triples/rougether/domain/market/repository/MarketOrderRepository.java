package com.triples.rougether.domain.market.repository;

import com.triples.rougether.domain.market.entity.MarketOrder;
import com.triples.rougether.domain.market.entity.OrderSide;
import com.triples.rougether.domain.market.entity.OrderStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MarketOrderRepository extends JpaRepository<MarketOrder, Long> {

    Optional<MarketOrder> findByIdAndUserId(Long id, Long userId);

    // 호가창 적재: OPEN 주문을 처리 순서대로
    List<MarketOrder> findByStatusOrderByEngineSeqAsc(OrderStatus status);

    // 반대 주문 금지 판정: 대기 중인 같은 종목·같은 방향 주문
    @Query("select count(o) > 0 from MarketOrder o where o.userId = :userId and o.assetId = :assetId "
            + "and o.status = com.triples.rougether.domain.market.entity.OrderStatus.OPEN and o.side = :side")
    boolean existsOpenSide(@Param("userId") Long userId, @Param("assetId") Long assetId,
                           @Param("side") OrderSide side);

    // 1인 1개 판정: 미체결 매수와 미체결 보유분 매도(맡긴 가구)를 보유로 셈.
    @Query("select count(o) > 0 from MarketOrder o where o.userId = :userId and o.assetId = :assetId "
            + "and o.status = com.triples.rougether.domain.market.entity.OrderStatus.OPEN "
            + "and (o.side = com.triples.rougether.domain.market.entity.OrderSide.BUY "
            + "or o.source = com.triples.rougether.domain.market.entity.OrderSource.INVENTORY)")
    boolean existsOpenHolding(@Param("userId") Long userId, @Param("assetId") Long assetId);
}
