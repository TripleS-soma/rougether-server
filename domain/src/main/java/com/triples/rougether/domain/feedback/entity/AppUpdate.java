package com.triples.rougether.domain.feedback.entity;

import com.triples.rougether.domain.support.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "app_updates")
@EntityListeners(AuditingEntityListener.class)
public class AppUpdate extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100)
    private String title;
    @Column(nullable = false, length = 5000)
    private String content;

    public static AppUpdate create(String title, String content) {
        AppUpdate entry = new AppUpdate();
        entry.title = title.trim();
        entry.content = content.trim();
        return entry;
    }
}
