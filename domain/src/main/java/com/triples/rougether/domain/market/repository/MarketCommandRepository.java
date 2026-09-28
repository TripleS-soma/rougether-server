package com.triples.rougether.domain.market.repository;

import com.triples.rougether.domain.market.entity.CommandStatus;
import com.triples.rougether.domain.market.entity.MarketCommand;
import com.triples.rougether.domain.market.entity.OrderSide;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MarketCommandRepository extends JpaRepository<MarketCommand, Long> {

    Optional<MarketCommand> findByUserIdAndRequestId(Long userId, String requestId);

    Optional<MarketCommand> findByIdAndUserId(Long id, Long userId);

    // 엔진 폴링: 처리 전 접수를 접수 순서로
    List<MarketCommand> findByStatusOrderByIdAsc(CommandStatus status, Pageable page);

    // 결정론 재생 검증용: 처리 순서대로
    List<MarketCommand> findByEngineSeqIsNotNullOrderByEngineSeqAsc();

    // 실패 횟수는 처리 트랜잭션이 롤백돼도 남아야 하므로 별도 트랜잭션에서 올림
    @Modifying
    @Query("update MarketCommand c set c.attempts = c.attempts + 1 where c.id = :id")
    int incrementAttempts(@Param("id") Long id);

    // 반대 주문 금지 판정: 엔진 처리 전 접수 중 같은 종목·같은 방향
    @Query("select count(c) > 0 from MarketCommand c where c.userId = :userId and c.assetId = :assetId "
            + "and c.status = com.triples.rougether.domain.market.entity.CommandStatus.PENDING "
            + "and c.type = com.triples.rougether.domain.market.entity.CommandType.PLACE and c.side = :side")
    boolean existsPendingPlace(@Param("userId") Long userId, @Param("assetId") Long assetId,
                               @Param("side") OrderSide side);

    // 1인 1개 판정: 엔진이 아직 처리하지 않은 매수·보유분 매도 접수도 보유로 셈.
    @Query("select count(c) > 0 from MarketCommand c where c.userId = :userId and c.assetId = :assetId "
            + "and c.status = com.triples.rougether.domain.market.entity.CommandStatus.PENDING "
            + "and c.type = com.triples.rougether.domain.market.entity.CommandType.PLACE "
            + "and (c.side = com.triples.rougether.domain.market.entity.OrderSide.BUY "
            + "or c.source = com.triples.rougether.domain.market.entity.OrderSource.INVENTORY)")
    boolean existsPendingHolding(@Param("userId") Long userId, @Param("assetId") Long assetId);
}
