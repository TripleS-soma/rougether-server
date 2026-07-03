package com.triples.rougether.domain.character.entity;

import com.triples.rougether.domain.member.entity.User;
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
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "user_characters")
public class UserCharacter extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id", nullable = false)
    private Character character;

    @Column(name = "is_selected", nullable = false)
    private boolean selected;

    @Column(name = "acquired_at", nullable = false)
    private Instant acquiredAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    private UserCharacter(User user, Character character) {
        this.user = user;
        this.character = character;
        this.selected = false;
        this.acquiredAt = Instant.now();
    }

    // 캐릭터 지급(뽑기). 착용(selected)은 false 로 시작.
    public static UserCharacter create(User user, Character character) {
        return new UserCharacter(user, character);
    }

    private UserCharacter(User user, Character character, Instant acquiredAt, boolean selected) {
        this.user = user;
        this.character = character;
        this.acquiredAt = acquiredAt;
        this.selected = selected;
    }

    public static UserCharacter of(User user, Character character, Instant acquiredAt, boolean selected) {
        return new UserCharacter(user, character, acquiredAt, selected);
    }

    public void select() {
        this.selected = true;
    }

    public void unselect() {
        this.selected = false;
    }
}
