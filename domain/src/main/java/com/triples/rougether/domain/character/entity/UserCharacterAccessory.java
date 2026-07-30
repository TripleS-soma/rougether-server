package com.triples.rougether.domain.character.entity;

import com.triples.rougether.domain.shop.entity.UserItem;
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
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "user_character_accessories", uniqueConstraints = {
        @UniqueConstraint(name = "uq_uchar_accessory_slot",
                columnNames = {"user_character_id", "character_slot_type"}),
        @UniqueConstraint(name = "uq_uchar_accessory_item",
                columnNames = {"user_character_id", "user_item_id"})
})
public class UserCharacterAccessory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_character_id", nullable = false)
    private UserCharacter userCharacter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_item_id", nullable = false)
    private UserItem userItem;

    @Column(name = "character_slot_type", length = 40, nullable = false)
    private String characterSlotType;

    @Column(name = "equipped_at", nullable = false)
    private Instant equippedAt;

    private UserCharacterAccessory(UserCharacter userCharacter, UserItem userItem,
                                   String characterSlotType) {
        this.userCharacter = userCharacter;
        this.userItem = userItem;
        this.characterSlotType = characterSlotType;
        this.equippedAt = Instant.now();
    }

    public static UserCharacterAccessory equip(UserCharacter userCharacter, UserItem userItem,
                                               String characterSlotType) {
        return new UserCharacterAccessory(userCharacter, userItem, characterSlotType);
    }

    public void replaceWith(UserItem userItem) {
        this.userItem = userItem;
        this.equippedAt = Instant.now();
    }
}
