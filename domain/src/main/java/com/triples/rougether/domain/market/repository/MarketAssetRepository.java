package com.triples.rougether.domain.market.repository;

import com.triples.rougether.domain.market.entity.MarketAsset;
import com.triples.rougether.domain.market.entity.MarketAssetStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MarketAssetRepository extends JpaRepository<MarketAsset, Long> {

    boolean existsByItemId(Long itemId);

    // 종목 카드 목록: 거래 중인 종목을 최근 상장순으로. 요청자(viewer)가 차단한 제작자의 종목은 뺌(#399).
    // 제작자가 탈퇴해 creator_user_id 가 null 이면 차단 대상이 아니므로 그대로 노출함.
    @Query(value = "select a from MarketAsset a where a.status = :status "
            + "and exists (select o.id from MarketOrder o where o.assetId = a.id "
            + "and o.side = com.triples.rougether.domain.market.entity.OrderSide.SELL "
            + "and o.status = com.triples.rougether.domain.market.entity.OrderStatus.OPEN) "
            + "and not exists (select b.id from UserBlock b where b.blockerUserId = :viewer "
            + "and b.blockedUserId = a.creatorUserId) order by a.id desc",
            countQuery = "select count(a) from MarketAsset a where a.status = :status "
            + "and exists (select o.id from MarketOrder o where o.assetId = a.id "
            + "and o.side = com.triples.rougether.domain.market.entity.OrderSide.SELL "
            + "and o.status = com.triples.rougether.domain.market.entity.OrderStatus.OPEN) "
            + "and not exists (select b.id from UserBlock b where b.blockerUserId = :viewer "
            + "and b.blockedUserId = a.creatorUserId)")
    Page<MarketAsset> findListedFor(@Param("viewer") Long viewer, @Param("status") MarketAssetStatus status,
                                    Pageable page);

    @Query("select a from MarketAsset a where a.creatorUserId = :viewer or exists "
            + "(select ui.id from UserItem ui where ui.user.id = :viewer and ui.item.id = a.itemId "
            + "and ui.deletedAt is null) or exists (select o.id from MarketOrder o where o.assetId = a.id "
            + "and o.userId = :viewer and o.side = com.triples.rougether.domain.market.entity.OrderSide.SELL "
            + "and o.status = com.triples.rougether.domain.market.entity.OrderStatus.OPEN) "
            + "or exists (select c.id from MarketCommand c where c.assetId = a.id and c.userId = :viewer "
            + "and c.type = com.triples.rougether.domain.market.entity.CommandType.PLACE "
            + "and c.side = com.triples.rougether.domain.market.entity.OrderSide.SELL "
            + "and c.status = com.triples.rougether.domain.market.entity.CommandStatus.PENDING) order by a.id desc")
    Page<MarketAsset> findManagedFor(@Param("viewer") Long viewer, Pageable page);

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
