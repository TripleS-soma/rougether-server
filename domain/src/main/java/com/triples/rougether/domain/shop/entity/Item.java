package com.triples.rougether.domain.shop.entity;

import com.triples.rougether.domain.shared.CurrencyType;
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
@Table(name = "items")
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "theme_id", nullable = false)
    private Theme theme;

    @Column(name = "category_code", length = 50, nullable = false)
    private String categoryCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "placement_type", length = 40, nullable = false)
    private PlacementType placementType;

    @Column(name = "surface_slot_type", length = 40)
    private String surfaceSlotType;

    @Column(name = "character_slot_type", length = 40)
    private String characterSlotType;

    @Column(name = "name", length = 120, nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "purchase_currency_type", length = 30)
    private CurrencyType purchaseCurrencyType;

    @Column(name = "price_amount")
    private Integer priceAmount;

    @Column(name = "asset_key", length = 255, nullable = false)
    private String assetKey;

    @Column(name = "is_limited", nullable = false)
    private boolean limited;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    private Item(
            Theme theme,
            String categoryCode,
            PlacementType placementType,
            String surfaceSlotType,
            String characterSlotType,
            String name,
            CurrencyType purchaseCurrencyType,
            Integer priceAmount,
            String assetKey,
            boolean limited,
            boolean active) {
        this.theme = theme;
        this.categoryCode = categoryCode;
        this.placementType = placementType;
        this.surfaceSlotType = surfaceSlotType;
        this.characterSlotType = characterSlotType;
        this.name = name;
        this.purchaseCurrencyType = purchaseCurrencyType;
        this.priceAmount = priceAmount;
        this.assetKey = assetKey;
        this.limited = limited;
        this.active = active;
    }

    public static Item create(
            Theme theme,
            String categoryCode,
            PlacementType placementType,
            String surfaceSlotType,
            String characterSlotType,
            String name,
            CurrencyType purchaseCurrencyType,
            Integer priceAmount,
            String assetKey,
            boolean limited,
            boolean active) {
        return new Item(
                theme,
                categoryCode,
                placementType,
                surfaceSlotType,
                characterSlotType,
                name,
                purchaseCurrencyType,
                priceAmount,
                assetKey,
                limited,
                active);
    }

    public void update(
            Theme theme,
            String categoryCode,
            PlacementType placementType,
            String surfaceSlotType,
            String characterSlotType,
            String name,
            CurrencyType purchaseCurrencyType,
            Integer priceAmount,
            String assetKey,
            boolean limited,
            boolean active) {
        this.theme = theme;
        this.categoryCode = categoryCode;
        this.placementType = placementType;
        this.surfaceSlotType = surfaceSlotType;
        this.characterSlotType = characterSlotType;
        this.name = name;
        this.purchaseCurrencyType = purchaseCurrencyType;
        this.priceAmount = priceAmount;
        this.assetKey = assetKey;
        this.limited = limited;
        this.active = active;
    }
}
