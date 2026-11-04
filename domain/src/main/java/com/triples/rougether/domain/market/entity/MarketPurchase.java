package com.triples.rougether.domain.market.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 구매 영수증. 가구 지급 및 양쪽 코인 원장과 같은 트랜잭션으로 저장함.
@Getter
@Entity
@Table(name = "market_purchases")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketPurchase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long assetId;
    @Column(nullable = false) private Long buyerUserId;
    @Column(nullable = false) private Long sellerUserId;
    @Column(nullable = false, length = 36) private String requestId;
    @Column(nullable = false) private int price;
    @Column(nullable = false) private int feeAmount;
    @Column(nullable = false) private Long userItemId;
    @Column(nullable = false) private Instant createdAt;

    public MarketPurchase(Long assetId, Long buyer, Long seller, String requestId, int price,
                          int fee, Long userItemId, Instant now) {
        this.assetId = assetId;
        this.buyerUserId = buyer;
        this.sellerUserId = seller;
        this.requestId = requestId;
        this.price = price;
        this.feeAmount = fee;
        this.userItemId = userItemId;
        this.createdAt = now;
    }
}
