package com.triples.rougether.domain.market.repository;

import com.triples.rougether.domain.market.entity.MarketAsset;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MarketAssetRepository extends JpaRepository<MarketAsset, Long> {

    boolean existsByItemId(Long itemId);

    // 발행 재고 매도 에스크로용. 동시 매도의 재고 이중 차감을 막음.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from MarketAsset a where a.id = :id")
    Optional<MarketAsset> findWithLockById(@Param("id") Long id);

    // 재검수 거절 판정용 잠금 읽기(FOR SHARE). 재검수 트랜잭션의 스냅샷과 무관하게 최신 커밋된 상장을 봄.
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select a from MarketAsset a where a.itemId = "
            + "(select ui.item.id from UserItem ui where ui.id = :userItemId)")
    Optional<MarketAsset> findListedForShareByUserItemId(@Param("userItemId") Long userItemId);
}
