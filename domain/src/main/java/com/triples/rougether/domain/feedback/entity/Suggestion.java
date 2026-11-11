package com.triples.rougether.domain.feedback.entity;

import com.triples.rougether.domain.support.BaseEntity;
import com.triples.rougether.domain.member.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "suggestions")
@EntityListeners(AuditingEntityListener.class)
public class Suggestion extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(nullable = false, length = 100)
    private String title;
    @Column(nullable = false, length = 2000)
    private String content;

    public static Suggestion create(User user, String title, String content) {
        Suggestion entry = new Suggestion();
        entry.user = user;
        entry.title = title.trim();
        entry.content = content.trim();
        return entry;
    }
}
