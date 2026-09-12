package com.triples.rougether.domain.minigame.entity;

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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "minigame_best_scores",
        uniqueConstraints = @UniqueConstraint(name = "uq_minigame_best_score_game_user",
                columnNames = {"game_code", "user_id"}))
public class MinigameBestScore extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "game_code", length = 40, nullable = false, updatable = false)
    private String gameCode;

    @Column(name = "rules_version", nullable = false)
    private int rulesVersion;

    @Column(nullable = false)
    private int score;

    @Column(name = "achieved_at", nullable = false)
    private Instant achievedAt;

    private MinigameBestScore(User user, String gameCode, int rulesVersion, int score, Instant achievedAt) {
        this.user = user;
        this.gameCode = gameCode;
        this.rulesVersion = rulesVersion;
        this.score = score;
        this.achievedAt = achievedAt;
    }

    public static MinigameBestScore create(User user, String gameCode, int rulesVersion,
                                           int score, Instant achievedAt) {
        return new MinigameBestScore(user, gameCode, rulesVersion, score, achievedAt);
    }

    // 동점에서는 최초 달성 시각과 적용 규칙을 보존함.
    public void improve(int score, int rulesVersion, Instant achievedAt) {
        if (score > this.score) {
            this.score = score;
            this.rulesVersion = rulesVersion;
            this.achievedAt = achievedAt;
        }
    }
}
