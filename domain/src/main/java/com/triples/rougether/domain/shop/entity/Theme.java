package com.triples.rougether.domain.shop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.Map;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "themes")
public class Theme implements com.triples.rougether.domain.i18n.LocalizedName {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", length = 50, nullable = false)
    private String code;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "name_translations")
    private Map<String, String> nameTranslations;

    public void updateNameTranslations(Map<String, String> translations) {
        this.nameTranslations = Map.copyOf(translations);
    }

    @Column(name = "cover_image_key", length = 255)
    private String coverImageKey;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    public Theme(String code, String name, String coverImageKey, boolean active) {
        this.code = code;
        this.name = name;
        this.coverImageKey = coverImageKey;
        this.active = active;
    }
}
