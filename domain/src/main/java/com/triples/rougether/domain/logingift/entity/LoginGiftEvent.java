package com.triples.rougether.domain.logingift.entity;

import com.triples.rougether.domain.shop.entity.Item;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

@Entity
@Table(name = "login_gift_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoginGiftEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 50)
    private String code;
    @Column(nullable = false, length = 120)
    private String title;
    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;
    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;
    @Column(name = "coin_amount", nullable = false)
    private int coinAmount;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reward_item_id", nullable = false)
    private Item rewardItem;
    @Column(name = "is_enabled", nullable = false)
    private boolean enabled;

    public boolean accepts(Instant at) {
        return enabled && !at.isBefore(startsAt) && at.isBefore(endsAt);
    }
}
