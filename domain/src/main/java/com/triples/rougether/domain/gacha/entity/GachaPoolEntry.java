package com.triples.rougether.domain.gacha.entity;

import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shop.entity.Item;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "gacha_pool_entries")
public class GachaPoolEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "gacha_id", nullable = false)
    private Gacha gacha;

    @Enumerated(EnumType.STRING)
    @Column(name = "reward_type", length = 30, nullable = false)
    private RewardType rewardType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id")
    private Item item;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id")
    private Character character;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_type", length = 30)
    private CurrencyType currencyType;

    @Column(name = "reward_amount")
    private Integer rewardAmount;

    @Column(name = "rarity", length = 30)
    private String rarity;

    @Column(name = "weight", nullable = false)
    private int weight;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    public void updateRarity(String rarity) {
        if (!GachaRarity.isSupported(rarity)) {
            throw new IllegalArgumentException("지원하지 않는 뽑기 등급입니다: " + rarity);
        }
        this.rarity = rarity;
    }
}
