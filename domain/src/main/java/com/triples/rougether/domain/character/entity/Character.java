package com.triples.rougether.domain.character.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "characters")
public class Character {

    public static final String MORU_CODE = "moru";

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

    @OneToMany(mappedBy = "character")
    @OrderBy("sortOrder ASC, id ASC")
    private List<CharacterPose> poses = new ArrayList<>();

    public Character(String code, String name, String baseAssetKey, int sortOrder, boolean active) {
        this.code = code;
        this.name = name;
        this.baseAssetKey = baseAssetKey;
        this.sortOrder = sortOrder;
        this.active = active;
    }

    // 모루는 개인 방 레벨 달성으로 획득함. 카탈로그의 대소문자 차이도 우회로 허용하지 않음.
    public boolean isRoomLevelReward() {
        return MORU_CODE.equalsIgnoreCase(code);
    }

    // admin 카탈로그 화면의 사용/미사용 토글.
    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }
}
