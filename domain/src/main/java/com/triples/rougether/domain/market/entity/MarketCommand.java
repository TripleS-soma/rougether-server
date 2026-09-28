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
// 매칭 엔진이 engine_seq(공식 처리 순서)를 부여하며 처리함. 생성·처리 메서드는 주문 접수·엔진 이슈에서 추가.
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

    // 접수 시 맡아 둔 코인(매수) 또는 수량(매도)
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
}
