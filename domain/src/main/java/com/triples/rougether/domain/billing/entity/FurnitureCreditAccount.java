package com.triples.rougether.domain.billing.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "furniture_credit_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FurnitureCreditAccount {
    @Id private Long userId;
    @Column(nullable = false, unique = true, length = 36) private String accountToken;
    @Column(nullable = false) private long balance;
    @Column(nullable = false) private long reserved;
    private Instant lastVerificationAt;

    public FurnitureCreditAccount(Long userId) { this.userId = userId; this.accountToken = UUID.randomUUID().toString(); }
    public void adjust(long amount) { balance = Math.addExact(balance, amount); }
    public void reserve() {
        if (balance < 1) throw new IllegalStateException("생성권 잔액 부족");
        balance--; reserved++;
    }
    public void settle(boolean success) {
        if (reserved < 1) throw new IllegalStateException("예약된 생성권 없음");
        reserved--;
        if (!success) balance++;
    }
    public void verifiedAt(Instant now) { lastVerificationAt = now; }
}
