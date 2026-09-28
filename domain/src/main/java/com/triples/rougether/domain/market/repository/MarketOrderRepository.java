package com.triples.rougether.domain.market.repository;

import com.triples.rougether.domain.market.entity.MarketOrder;
import com.triples.rougether.domain.market.entity.OrderSide;
import com.triples.rougether.domain.market.entity.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MarketOrderRepository extends JpaRepository<MarketOrder, Long> {

    Optional<MarketOrder> findByIdAndUserId(Long id, Long userId);

    // 호가창 적재: OPEN 주문을 처리 순서대로
    List<MarketOrder> findByStatusOrderByEngineSeqAsc(OrderStatus status);

    // 만료 대상: 유효기간이 지난 대기 주문 중 아직 만료 접수가 없는 것을 오래된 순으로. (status, expires_at) 인덱스를 씀.
    // 이미 접수가 있는 주문을 빼야, 만료 처리에 실패해 OPEN 으로 남은 주문이 배치 앞자리를 계속 차지하지 않음.
    @Query("select o from MarketOrder o where o.status = com.triples.rougether.domain.market.entity.OrderStatus.OPEN "
            + "and o.expiresAt <= :now and not exists (select c.id from MarketCommand c "
            + "where c.type = com.triples.rougether.domain.market.entity.CommandType.EXPIRE and c.targetOrderId = o.id) "
            + "order by o.expiresAt asc, o.id asc")
    List<MarketOrder> findExpirable(@Param("now") Instant now, Pageable page);

    // 만료 접수가 거절됐는데 아직 OPEN 인 주문 수(운영 확인용)
    @Query("select count(o) from MarketOrder o where o.status = com.triples.rougether.domain.market.entity.OrderStatus.OPEN "
            + "and o.expiresAt <= :now and exists (select c.id from MarketCommand c "
            + "where c.type = com.triples.rougether.domain.market.entity.CommandType.EXPIRE and c.targetOrderId = o.id "
            + "and c.status = com.triples.rougether.domain.market.entity.CommandStatus.REJECTED)")
    long countStuckExpired(@Param("now") Instant now);

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
