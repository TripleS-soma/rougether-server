package com.triples.rougether.domain.billing.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "furniture_credit_reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FurnitureCreditReservation {
    public enum Status { RESERVED, SPENT, RELEASED }
    public enum PaymentType { CREDIT, COIN }
    @Id @Column(length = 36) private String jobId;
    @Column(nullable = false) private Long userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private PaymentType paymentType;
    @Column(nullable = false) private int paymentAmount;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    public FurnitureCreditReservation(String jobId, Long userId, Instant now) {
        this(jobId, userId, PaymentType.CREDIT, 1, now);
    }
    public FurnitureCreditReservation(String jobId, Long userId, PaymentType paymentType, int paymentAmount, Instant now) {
        this.jobId = jobId; this.userId = userId; this.status = Status.RESERVED;
        this.paymentType = paymentType; this.paymentAmount = paymentAmount;
        this.createdAt = now; this.updatedAt = now;
    }
    public void settle(boolean success, Instant now) { status = success ? Status.SPENT : Status.RELEASED; updatedAt = now; }
}
