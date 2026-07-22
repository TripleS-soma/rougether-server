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
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "items")
public class Item {

    private static final BigDecimal DEFAULT_SCALE = new BigDecimal("1.00");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "theme_id", nullable = false)
    private Theme theme;

    @Column(name = "category_code", length = 50, nullable = false)
    private String categoryCode;

    // 배치 방식(positioned / surface_slot)은 문자열로 보관, 해석은 프론트가 담당.
    // 배치 모델이 확정되면 그때 enum 화한다.
    @Column(name = "placement_type", length = 40, nullable = false)
    private String placementType;

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

    // positioned 가구의 기본 배치 슬롯(topLeft…bottomRight). surface 아이템은 null. admin 에서 조정 가능.
    @Column(name = "default_slot", length = 40)
    private String defaultSlot;

    @Column(name = "default_scale", precision = 4, scale = 2, nullable = false)
    private BigDecimal defaultScale = DEFAULT_SCALE;

    // FREE_V1 새 배치의 중심점 기준 기본 위치. 둘 다 null 이면 클라이언트 공통 중심점을 사용한다.
    @Column(name = "default_position_x", precision = 6, scale = 5)
    private BigDecimal defaultPositionX;

    @Column(name = "default_position_y", precision = 6, scale = 5)
    private BigDecimal defaultPositionY;

    public Item(Theme theme, String categoryCode, String placementType, String surfaceSlotType,
                String characterSlotType, String name, CurrencyType purchaseCurrencyType,
                Integer priceAmount, String assetKey, boolean limited, boolean active) {
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

    // admin 에서 배치 슬롯 조정.
    public void updateDefaultSlot(String defaultSlot) {
        this.defaultSlot = defaultSlot;
    }

    // admin 에서 기본 렌더링 배율 조정.
    public void updateDefaultScale(BigDecimal defaultScale) {
        this.defaultScale = defaultScale;
    }

    // admin 에서 FREE_V1 새 배치의 기본 렌더링 값을 한 번에 조정.
    public void updateRenderDefaults(BigDecimal defaultScale,
                                     BigDecimal defaultPositionX,
                                     BigDecimal defaultPositionY) {
        this.defaultScale = defaultScale;
        this.defaultPositionX = defaultPositionX;
        this.defaultPositionY = defaultPositionY;
    }
}
