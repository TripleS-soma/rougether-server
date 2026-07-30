package com.triples.rougether.domain.character.entity;

import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.support.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "character_accessory_render_profiles", uniqueConstraints = {
        @UniqueConstraint(
                name = "uq_accessory_render_profile",
                columnNames = {"item_id", "character_id", "render_state"})
})
public class CharacterAccessoryRenderProfile extends BaseEntity {

    public static final String DEFAULT_STATE = "default";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id", nullable = false)
    private Character character;

    @Column(name = "render_state", length = 40, nullable = false)
    private String renderState;

    @Column(name = "asset_key", length = 255, nullable = false)
    private String assetKey;

    @Column(name = "canvas_width", nullable = false)
    private int canvasWidth;

    @Column(name = "canvas_height", nullable = false)
    private int canvasHeight;

    @Column(name = "asset_width", nullable = false)
    private int assetWidth;

    @Column(name = "asset_height", nullable = false)
    private int assetHeight;

    @Column(name = "position_x", precision = 6, scale = 5, nullable = false)
    private BigDecimal positionX;

    @Column(name = "position_y", precision = 6, scale = 5, nullable = false)
    private BigDecimal positionY;

    @Column(name = "width_ratio", precision = 5, scale = 4, nullable = false)
    private BigDecimal widthRatio;

    @Column(name = "rotation_deg", nullable = false)
    private int rotationDeg;

    @Column(name = "z_index", nullable = false)
    private int zIndex;

    public CharacterAccessoryRenderProfile(
            Item item,
            Character character,
            String renderState,
            String assetKey,
            int canvasWidth,
            int canvasHeight,
            int assetWidth,
            int assetHeight,
            BigDecimal positionX,
            BigDecimal positionY,
            BigDecimal widthRatio,
            int rotationDeg,
            int zIndex) {
        this.item = item;
        this.character = character;
        update(
                renderState,
                assetKey,
                canvasWidth,
                canvasHeight,
                assetWidth,
                assetHeight,
                positionX,
                positionY,
                widthRatio,
                rotationDeg,
                zIndex);
    }

    public void update(
            String renderState,
            String assetKey,
            int canvasWidth,
            int canvasHeight,
            int assetWidth,
            int assetHeight,
            BigDecimal positionX,
            BigDecimal positionY,
            BigDecimal widthRatio,
            int rotationDeg,
            int zIndex) {
        this.renderState = renderState;
        this.assetKey = assetKey;
        this.canvasWidth = canvasWidth;
        this.canvasHeight = canvasHeight;
        this.assetWidth = assetWidth;
        this.assetHeight = assetHeight;
        this.positionX = positionX;
        this.positionY = positionY;
        this.widthRatio = widthRatio;
        this.rotationDeg = rotationDeg;
        this.zIndex = zIndex;
    }
}
