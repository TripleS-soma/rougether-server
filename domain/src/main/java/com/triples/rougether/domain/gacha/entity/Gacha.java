package com.triples.rougether.domain.gacha.entity;

import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.support.BaseEntity;
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
@Table(name = "gacha")
public class Gacha extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", length = 50, nullable = false)
    private String code;

    @Column(name = "name", length = 120, nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "cost_currency_type", length = 30)
    private CurrencyType costCurrencyType;

    @Column(name = "cost_amount", nullable = false)
    private int costAmount;

    @Column(name = "draw_count", nullable = false)
    private int drawCount;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    // 가구 뽑기는 테마별, 캐릭터 뽑기는 테마 무관(null).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "theme_id")
    private Theme theme;
}
