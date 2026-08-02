package com.triples.rougether.domain.moderation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

// 금칙어 1건 (#209). word 는 정규화(TextNormalizer) 결과로 저장 - 매칭과 같은 형태 유지.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "banned_words")
@EntityListeners(AuditingEntityListener.class)
public class BannedWord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "word", length = 50, nullable = false, unique = true)
    private String word;

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static BannedWord of(String normalizedWord) {
        BannedWord bannedWord = new BannedWord();
        bannedWord.word = normalizedWord;
        return bannedWord;
    }
}
