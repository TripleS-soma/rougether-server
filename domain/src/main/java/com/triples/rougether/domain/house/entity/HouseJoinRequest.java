package com.triples.rougether.domain.house.entity;

import com.triples.rougether.domain.member.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "house_join_requests")
public class HouseJoinRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "house_id", nullable = false)
    private House house;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private HouseJoinRequestStatus status;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    public static HouseJoinRequest create(House house, User user) {
        HouseJoinRequest request = new HouseJoinRequest();
        request.house = house;
        request.user = user;
        request.status = HouseJoinRequestStatus.PENDING;
        request.requestedAt = Instant.now();
        return request;
    }

    // 거절 후 재신청은 UNIQUE(house_id, user_id) 행을 재사용함.
    public void reopen() {
        this.status = HouseJoinRequestStatus.PENDING;
        this.requestedAt = Instant.now();
        this.processedAt = null;
    }

    public void accept() {
        this.status = HouseJoinRequestStatus.ACCEPTED;
        this.processedAt = Instant.now();
    }

    public void reject() {
        this.status = HouseJoinRequestStatus.REJECTED;
        this.processedAt = Instant.now();
    }

    public boolean isPending() {
        return status == HouseJoinRequestStatus.PENDING;
    }
}
