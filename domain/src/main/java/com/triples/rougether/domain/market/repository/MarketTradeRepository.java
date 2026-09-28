package com.triples.rougether.domain.market.repository;

import com.triples.rougether.domain.market.entity.MarketTrade;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MarketTradeRepository extends JpaRepository<MarketTrade, Long> {

    // 최근 체결 목록(최신순)
    Page<MarketTrade> findByAssetIdOrderByIdDesc(Long assetId, Pageable page);

    // 최근 체결가
    Optional<MarketTrade> findFirstByAssetIdOrderByIdDesc(Long assetId);

    // 종목 카드용: 여러 종목의 마지막 체결을 한 번에
    @Query("select t from MarketTrade t where t.id in "
            + "(select max(t2.id) from MarketTrade t2 where t2.assetId in :assetIds group by t2.assetId)")
    List<MarketTrade> findLatestByAssetIds(@Param("assetIds") Collection<Long> assetIds);
}
