package com.triples.rougether.domain.market.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 거래소 체결 내역(#406). 수정하지 않음. fee_amount 는 소각된 수수료.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "market_trades")
public class MarketTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_id", nullable = false, updatable = false)
    private Long assetId;

    @Column(name = "engine_seq", nullable = false, updatable = false)
    private long engineSeq;

    @Column(name = "buy_order_id", nullable = false, updatable = false)
    private Long buyOrderId;

    @Column(name = "sell_order_id", nullable = false, updatable = false)
    private Long sellOrderId;

    @Column(name = "buyer_user_id", nullable = false, updatable = false)
    private Long buyerUserId;

    @Column(name = "seller_user_id", nullable = false, updatable = false)
    private Long sellerUserId;

    @Column(name = "royalty_user_id", updatable = false)
    private Long royaltyUserId;

    @Column(name = "price", nullable = false, updatable = false)
    private int price;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "royalty_amount", nullable = false, updatable = false)
    private int royaltyAmount;

    @Column(name = "fee_amount", nullable = false, updatable = false)
    private int feeAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static MarketTrade of(Long assetId, long engineSeq, MarketOrder buy, MarketOrder sell, Long royaltyUserId,
                                 int price, int quantity, int royaltyAmount, int feeAmount, Instant now) {
        MarketTrade trade = new MarketTrade();
        trade.assetId = assetId;
        trade.engineSeq = engineSeq;
        trade.buyOrderId = buy.getId();
        trade.sellOrderId = sell.getId();
        trade.buyerUserId = buy.getUserId();
        trade.sellerUserId = sell.getUserId();
        trade.royaltyUserId = royaltyUserId;
        trade.price = price;
        trade.quantity = quantity;
        trade.royaltyAmount = royaltyAmount;
        trade.feeAmount = feeAmount;
        trade.createdAt = now;
        return trade;
    }
}
