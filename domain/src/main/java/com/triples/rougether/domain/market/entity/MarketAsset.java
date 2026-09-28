package com.triples.rougether.domain.market.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 거래소 상장 종목(#406). AI 가구(items row) 1개당 1회 상장.
// unissued_quantity 는 제작자가 아직 팔지 않은 발행 재고. 제작자 보유분 1개는 발행 수량에 포함됨.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "market_assets")
public class MarketAsset {

    public static final int MIN_SUPPLY = 1;
    public static final int MAX_SUPPLY = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_id", nullable = false, updatable = false)
    private Long itemId;

    // 제작자 탈퇴 시 null(로열티 중단)
    @Column(name = "creator_user_id")
    private Long creatorUserId;

    @Column(name = "total_supply", nullable = false, updatable = false)
    private int totalSupply;

    @Column(name = "unissued_quantity", nullable = false)
    private int unissuedQuantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MarketAssetStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private MarketAsset(Long itemId, Long creatorUserId, int totalSupply, Instant now) {
        this.itemId = itemId;
        this.creatorUserId = creatorUserId;
        this.totalSupply = totalSupply;
        this.unissuedQuantity = totalSupply - 1;
        this.status = MarketAssetStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    // 발행. 제작자가 가진 1개를 뺀 나머지가 발행 재고가 됨. 수량 범위는 호출 측 검증 + DB CHECK 로 이중 방어.
    public static MarketAsset issue(Long itemId, Long creatorUserId, int totalSupply, Instant now) {
        if (totalSupply < MIN_SUPPLY || totalSupply > MAX_SUPPLY) {
            throw new IllegalArgumentException("totalSupply out of range: " + totalSupply);
        }
        return new MarketAsset(itemId, creatorUserId, totalSupply, now);
    }

    // 제작자의 발행 재고 매도 에스크로. 재고가 모자라면 false 를 돌려주고 아무것도 바꾸지 않음.
    public boolean takeUnissued(int quantity, Instant now) {
        if (quantity < 1 || quantity > unissuedQuantity) {
            return false;
        }
        this.unissuedQuantity -= quantity;
        this.updatedAt = now;
        return true;
    }

    // 발행 재고 매도가 거절·취소·만료되면 맡긴 수량을 재고로 되돌림. 잠금 조회한 인스턴스에만 호출할 것.
    public void returnUnissued(int quantity, Instant now) {
        if (quantity < 0 || unissuedQuantity + quantity > totalSupply) {
            throw new IllegalStateException("invalid unissued return for asset " + id);
        }
        this.unissuedQuantity += quantity;
        this.updatedAt = now;
    }

    public boolean isCreator(Long userId) {
        return creatorUserId != null && creatorUserId.equals(userId);
    }

    public boolean isSuspended() {
        return status == MarketAssetStatus.SUSPENDED;
    }
}
