package com.triples.rougether.domain.minigame.entity;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.support.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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
@Table(name = "minigame_runs")
public class MinigameRun extends BaseEntity {

    @Id
    @Column(length = 36, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "game_code", length = 40, nullable = false, updatable = false)
    private String gameCode;

    @Column(name = "rules_version", nullable = false, updatable = false)
    private int rulesVersion;

    @Column(nullable = false, updatable = false)
    private int seed;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    private Integer ticks;

    private Integer score;

    @Column(name = "finished_best_score")
    private Integer finishedBestScore;

    @Column(name = "personal_best")
    private Boolean personalBest;

    @Column(name = "finished_rank")
    private Long finishedRank;

    @Column(name = "submission_hash", length = 64)
    private String submissionHash;

    @Column(name = "finished_at")
    private Instant finishedAt;

    private MinigameRun(String id, User user, String gameCode, int rulesVersion, int seed,
                         Instant startedAt, Instant expiresAt) {
        this.id = id;
        this.user = user;
        this.gameCode = gameCode;
        this.rulesVersion = rulesVersion;
        this.seed = seed;
        this.startedAt = startedAt;
        this.expiresAt = expiresAt;
    }

    public static MinigameRun start(String id, User user, String gameCode, int rulesVersion, int seed,
                                    Instant startedAt, Instant expiresAt) {
        return new MinigameRun(id, user, gameCode, rulesVersion, seed, startedAt, expiresAt);
    }

    // 최초 완료 응답을 저장해 동일한 제출의 재요청에도 같은 결과를 반환함.
    public void finish(int ticks, int score, String submissionHash, int finishedBestScore,
                       boolean personalBest, long finishedRank, Instant finishedAt) {
        this.ticks = ticks;
        this.score = score;
        this.submissionHash = submissionHash;
        this.finishedBestScore = finishedBestScore;
        this.personalBest = personalBest;
        this.finishedRank = finishedRank;
        this.finishedAt = finishedAt;
    }

    public boolean isFinished() {
        return finishedAt != null;
    }
}
