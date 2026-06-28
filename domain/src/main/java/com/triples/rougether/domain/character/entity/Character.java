package com.triples.rougether.domain.character.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "characters")
public class Character {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", length = 50, nullable = false)
    private String code;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "base_asset_key", length = 255, nullable = false)
    private String baseAssetKey;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    private Character(String code, String name, String baseAssetKey, int sortOrder, boolean active) {
        this.code = code;
        this.name = name;
        this.baseAssetKey = baseAssetKey;
        this.sortOrder = sortOrder;
        this.active = active;
    }

    public static Character create(String code, String name, String baseAssetKey, int sortOrder, boolean active) {
        return new Character(code, name, baseAssetKey, sortOrder, active);
    }

    public void update(String code, String name, String baseAssetKey, int sortOrder, boolean active) {
        this.code = code;
        this.name = name;
        this.baseAssetKey = baseAssetKey;
        this.sortOrder = sortOrder;
        this.active = active;
    }
}
