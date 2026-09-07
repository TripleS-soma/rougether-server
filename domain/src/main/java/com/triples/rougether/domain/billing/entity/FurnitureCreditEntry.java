package com.triples.rougether.domain.billing.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "furniture_credit_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FurnitureCreditEntry {
    public enum Reason { ATTENDANCE_REWARD, PURCHASE, STORE_REFUND, STORE_REFUND_REVERSED, RESERVE, SPEND, RELEASE }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long userId;
    @Column(nullable = false, length = 36) private String referenceId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private Reason reason;
    @Column(nullable = false) private long amount;
    @Column(nullable = false) private long balanceAfter;
    @Column(nullable = false) private Instant createdAt;
    public FurnitureCreditEntry(Long userId, String referenceId, Reason reason, long amount, long balance, Instant now) {
        this.userId = userId; this.referenceId = referenceId; this.reason = reason;
        this.amount = amount; this.balanceAfter = balance; this.createdAt = now;
    }
}
