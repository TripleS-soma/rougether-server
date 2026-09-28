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

// 거래소 주문(#406). OPEN 주문 전체가 호가창의 정본이고 엔진 메모리 호가창은 캐시임.
// escrow_remaining 은 아직 맡아 둔 코인(매수) 또는 수량(매도). 생성·체결 메서드는 엔진 이슈에서 추가.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "market_orders")
public class MarketOrder {

    public static final int MIN_PRICE = 1;
    public static final int MAX_PRICE = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "command_id", nullable = false, updatable = false)
    private Long commandId;

    @Column(name = "asset_id", nullable = false, updatable = false)
    private Long assetId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 4, updatable = false)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 10, updatable = false)
    private OrderSource source;

    @Column(name = "user_item_id", updatable = false)
    private Long userItemId;

    @Column(name = "price", nullable = false, updatable = false)
    private int price;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "filled_quantity", nullable = false)
    private int filledQuantity;

    @Column(name = "escrow_remaining", nullable = false)
    private int escrowRemaining;

    @Column(name = "engine_seq", nullable = false, updatable = false)
    private long engineSeq;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private OrderStatus status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
