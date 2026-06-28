package com.triples.rougether.domain.routine.entity;

import com.triples.rougether.domain.member.entity.User;
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
@Table(name = "categories")
public class Category extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "color_hex", length = 20)
    private String colorHex;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", length = 30)
    private PrivacyScope visibility;

    @Column(name = "icon_key", length = 100)
    private String iconKey;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    private Category(User user, String name, String colorHex, String iconKey,
                     int sortOrder, PrivacyScope visibility) {
        this.user = user;
        this.name = name;
        this.colorHex = colorHex;
        this.iconKey = iconKey;
        this.sortOrder = sortOrder;
        this.visibility = visibility;
    }

    public static Category create(User user, String name, String colorHex, String iconKey,
                                  int sortOrder, PrivacyScope visibility) {
        return new Category(user, name, colorHex, iconKey, sortOrder, visibility);
    }

    public void update(String name, String colorHex, String iconKey,
                       Integer sortOrder, PrivacyScope visibility) {
        // name은 NOT NULL 업무필수라 공백이면 덮어쓰지 않음
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
        if (colorHex != null) {
            this.colorHex = colorHex;
        }
        if (iconKey != null) {
            this.iconKey = iconKey;
        }
        if (sortOrder != null) {
            this.sortOrder = sortOrder;
        }
        if (visibility != null) {
            this.visibility = visibility;
        }
    }

    public void softDelete(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }
}
