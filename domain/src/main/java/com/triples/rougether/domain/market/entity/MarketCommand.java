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

// 거래소 접수 대장(#406). API 가 에스크로와 같은 트랜잭션에서 PENDING 으로 넣고,
// 매칭 엔진이 engine_seq(공식 처리 순서)를 부여하며 처리함.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "market_commands")
public class MarketCommand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "request_id", nullable = false, length = 64, updatable = false)
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10, updatable = false)
    private CommandType type;

    @Column(name = "asset_id", nullable = false, updatable = false)
    private Long assetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", length = 4, updatable = false)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 10, updatable = false)
    private OrderSource source;

    @Column(name = "price", updatable = false)
    private Integer price;

    @Column(name = "quantity", updatable = false)
    private Integer quantity;

    // 접수 시 맡아 둔 것. 매수는 코인, 매도는 가구 수량(보유분 1 / 발행 재고 quantity). 엔진 환급 시 side 로 구분
    @Column(name = "escrow_amount", updatable = false)
    private Integer escrowAmount;

    @Column(name = "escrow_user_item_id", updatable = false)
    private Long escrowUserItemId;

    @Column(name = "target_order_id", updatable = false)
    private Long targetOrderId;

    @Column(name = "engine_seq")
    private Long engineSeq;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private CommandStatus status;

    @Column(name = "reject_code", length = 50)
    private String rejectCode;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "applied_at")
    private Instant appliedAt;

    // 주문 접수. 에스크로는 호출 측이 같은 트랜잭션에서 먼저 끝낸 뒤 이 접수를 넣음(#400).
    public static MarketCommand place(Long userId, String requestId, Long assetId, OrderSide side, OrderSource source,
                                      int price, int quantity, int escrowAmount, Long escrowUserItemId, Instant now) {
        MarketCommand command = pending(userId, requestId, CommandType.PLACE, assetId, now);
        command.side = side;
        command.source = source;
        command.price = price;
        command.quantity = quantity;
        command.escrowAmount = escrowAmount;
        command.escrowUserItemId = escrowUserItemId;
        return command;
    }

    // 주문 취소 접수. 환불은 엔진이 처리함.
    public static MarketCommand cancel(Long userId, String requestId, Long assetId, Long targetOrderId, Instant now) {
        MarketCommand command = pending(userId, requestId, CommandType.CANCEL, assetId, now);
        command.targetOrderId = targetOrderId;
        return command;
    }

    // 엔진이 처리 순서를 확정함(#401)
    public void stamp(long seq) {
        this.engineSeq = seq;
    }

    public boolean isPending() {
        return status == CommandStatus.PENDING;
    }

    public void applied(Long orderId, Instant now) {
        this.status = CommandStatus.APPLIED;
        this.orderId = orderId;
        this.appliedAt = now;
    }

    public void rejected(String code, Instant now) {
        this.status = CommandStatus.REJECTED;
        this.rejectCode = code;
        this.appliedAt = now;
    }

    // requestId 재요청 판정: 같은 주문 내용인지
    public boolean isSamePlace(Long assetId, OrderSide side, OrderSource source, int price, int quantity) {
        return type == CommandType.PLACE && this.assetId.equals(assetId) && this.side == side && this.source == source
                && this.price != null && this.price == price && this.quantity != null && this.quantity == quantity;
    }

    // requestId 재요청 판정: 같은 주문의 취소인지
    public boolean isSameCancel(Long orderId) {
        return type == CommandType.CANCEL && orderId.equals(targetOrderId);
    }

    private static MarketCommand pending(Long userId, String requestId, CommandType type, Long assetId, Instant now) {
        MarketCommand command = new MarketCommand();
        command.userId = userId;
        command.requestId = requestId;
        command.type = type;
        command.assetId = assetId;
        command.status = CommandStatus.PENDING;
        command.createdAt = now;
        return command;
    }
}
