package com.triples.rougether.domain.billing.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "furniture_credit_purchases")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FurnitureCreditPurchase {
    public enum Store { APPLE, GOOGLE }
    public enum Environment { PRODUCTION, SANDBOX }
    @Id @Column(length = 36) private String id;
    @Column(nullable = false) private Long userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Store store;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Environment environment;
    @Column(nullable = false, length = 64) private String referenceHash;
    @Column(nullable = false, length = 6000) private String referenceEncrypted;
    @Column(nullable = false, length = 200) private String productId;
    @Column(nullable = false) private int creditsPerUnit;
    @Column(nullable = false) private int quantity;
    @Column(nullable = false) private long grantedCredits;
    @Column(nullable = false) private long revokedCredits;
    private Long lastStoreSignedAt;
    @Column(nullable = false) private boolean storeConsumed;
    @Column(nullable = false) private Instant nextConsumeAt;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    public FurnitureCreditPurchase(Long userId, Store store, Environment environment, String referenceHash,
            String encrypted, String productId, int creditsPerUnit, int quantity, Instant now) {
        this.id = UUID.randomUUID().toString(); this.userId = userId; this.store = store;
        this.environment = environment; this.referenceHash = referenceHash; this.referenceEncrypted = encrypted;
        this.productId = productId; this.creditsPerUnit = creditsPerUnit; this.quantity = quantity;
        this.createdAt = now; this.updatedAt = now; this.nextConsumeAt = now;
    }

    // Apple은 서명 시각 순서대로 환불 취소도 반영함. Google은 환불된 수량을 다시 지급하지 않음.
    public long synchronize(int refundableQuantity, boolean consumed, Long signedAt, Instant now) {
        if (store == Store.APPLE && (signedAt == null || signedAt <= 0)) {
            throw new IllegalArgumentException("Apple 거래 서명 시각 누락");
        }
        if (store == Store.APPLE && lastStoreSignedAt != null && signedAt <= lastStoreSignedAt) return 0;
        long total = Math.multiplyExact((long) quantity, creditsPerUnit);
        long revoked = Math.multiplyExact((long) quantity - refundableQuantity, creditsPerUnit);
        long nextRevoked = store == Store.APPLE ? revoked : Math.max(revokedCredits, revoked);
        long delta = total - grantedCredits - (nextRevoked - revokedCredits);
        grantedCredits = total;
        revokedCredits = nextRevoked;
        if (store == Store.APPLE) lastStoreSignedAt = signedAt;
        storeConsumed |= consumed || store == Store.APPLE || refundableQuantity == 0;
        updatedAt = now;
        return delta;
    }
    public void deferConsumption(Instant next) { nextConsumeAt = next; }
    public void consumed(Instant now) { storeConsumed = true; updatedAt = now; }
}
