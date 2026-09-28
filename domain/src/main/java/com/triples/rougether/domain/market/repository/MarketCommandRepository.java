package com.triples.rougether.domain.market.repository;

import com.triples.rougether.domain.market.entity.MarketCommand;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MarketCommandRepository extends JpaRepository<MarketCommand, Long> {

    Optional<MarketCommand> findByUserIdAndRequestId(Long userId, String requestId);

    Optional<MarketCommand> findByIdAndUserId(Long id, Long userId);

    // 1인 1개 판정: 엔진이 아직 처리하지 않은 매수·보유분 매도 접수도 보유로 셈.
    @Query("select count(c) > 0 from MarketCommand c where c.userId = :userId and c.assetId = :assetId "
            + "and c.status = com.triples.rougether.domain.market.entity.CommandStatus.PENDING "
            + "and c.type = com.triples.rougether.domain.market.entity.CommandType.PLACE "
            + "and (c.side = com.triples.rougether.domain.market.entity.OrderSide.BUY "
            + "or c.source = com.triples.rougether.domain.market.entity.OrderSource.INVENTORY)")
    boolean existsPendingHolding(@Param("userId") Long userId, @Param("assetId") Long assetId);
}
