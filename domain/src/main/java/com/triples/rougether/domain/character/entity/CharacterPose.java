package com.triples.rougether.domain.character.entity;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "character_poses", uniqueConstraints = {
        @UniqueConstraint(
                name = "uq_character_pose_code",
                columnNames = {"character_id", "code"})
})
public class CharacterPose extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id", nullable = false)
    private Character character;

    @Column(name = "code", length = 40, nullable = false)
    private String code;

    @Column(name = "asset_key", length = 255, nullable = false)
    private String assetKey;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    public CharacterPose(
            Character character,
            String code,
            String assetKey,
            int sortOrder,
            boolean active) {
        this.character = character;
        update(code, assetKey, sortOrder, active);
    }

    public void update(String code, String assetKey, int sortOrder, boolean active) {
        this.code = code;
        this.assetKey = assetKey;
        this.sortOrder = sortOrder;
        this.active = active;
    }
}
