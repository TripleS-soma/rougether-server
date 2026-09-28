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
// escrow_remaining 은 아직 맡아 둔 코인(매수) 또는 수량(매도). 엔진만 생성·변경함(#401).
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

    public static final java.time.Duration TIME_TO_LIVE = java.time.Duration.ofDays(7);

    // 엔진이 주문 접수를 처리할 때 만듦. 만료는 접수 시각 + 7일.
    public static MarketOrder open(MarketCommand command, long engineSeq, Instant now) {
        MarketOrder order = new MarketOrder();
        order.commandId = command.getId();
        order.assetId = command.getAssetId();
        order.userId = command.getUserId();
        order.side = command.getSide();
        order.source = command.getSource();
        order.userItemId = command.getEscrowUserItemId();
        order.price = command.getPrice();
        order.quantity = command.getQuantity();
        order.escrowRemaining = command.getEscrowAmount();
        order.engineSeq = engineSeq;
        order.status = OrderStatus.OPEN;
        order.expiresAt = command.getCreatedAt().plus(TIME_TO_LIVE);
        order.createdAt = now;
        order.updatedAt = now;
        return order;
    }

    public int remaining() {
        return quantity - filledQuantity;
    }

    public boolean isOpen() {
        return status == OrderStatus.OPEN;
    }

    // 체결 반영. escrowUsed 는 매수면 코인(자기 가격 × 수량), 매도면 수량.
    public void fill(int qty, int escrowUsed, Instant now) {
        if (qty < 1 || qty > remaining() || escrowUsed > escrowRemaining) {
            throw new IllegalStateException("invalid fill for order " + id);
        }
        this.filledQuantity += qty;
        this.escrowRemaining -= escrowUsed;
        if (remaining() == 0) {
            this.status = OrderStatus.FILLED;
        }
        this.updatedAt = now;
    }

    // 취소·만료. 남은 에스크로는 호출 측이 돌려주고 여기서 0 으로 맞춤.
    public void close(OrderStatus closed, Instant now) {
        if (closed != OrderStatus.CANCELLED && closed != OrderStatus.EXPIRED) {
            throw new IllegalArgumentException("not a closing status: " + closed);
        }
        this.status = closed;
        this.escrowRemaining = 0;
        this.updatedAt = now;
    }
}
