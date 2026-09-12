CREATE TABLE minigame_runs (
    id                  VARCHAR(36) NOT NULL,
    user_id             BIGINT      NOT NULL,
    game_code           VARCHAR(40) NOT NULL,
    rules_version       INT         NOT NULL,
    seed                INT         NOT NULL,
    started_at          DATETIME(6) NOT NULL,
    expires_at          DATETIME(6) NOT NULL,
    ticks               INT         NULL,
    score               INT         NULL,
    finished_best_score INT         NULL,
    personal_best       BOOLEAN     NULL,
    finished_rank       BIGINT      NULL,
    submission_hash     VARCHAR(64) NULL,
    finished_at         DATETIME(6) NULL,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_minigame_run_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_minigame_run_rules_version CHECK (rules_version >= 1),
    CONSTRAINT ck_minigame_run_expiry CHECK (expires_at > started_at),
    CONSTRAINT ck_minigame_run_ticks CHECK (ticks >= 0),
    CONSTRAINT ck_minigame_run_score CHECK (score >= 0),
    CONSTRAINT ck_minigame_run_best_score CHECK (finished_best_score >= score),
    CONSTRAINT ck_minigame_run_rank CHECK (finished_rank >= 1),
    CONSTRAINT ck_minigame_run_finished_state CHECK (
        (finished_at IS NULL AND ticks IS NULL AND score IS NULL AND submission_hash IS NULL
            AND finished_best_score IS NULL AND personal_best IS NULL AND finished_rank IS NULL)
        OR
        (finished_at IS NOT NULL AND ticks IS NOT NULL AND score IS NOT NULL AND submission_hash IS NOT NULL
            AND finished_best_score IS NOT NULL AND personal_best IS NOT NULL AND finished_rank IS NOT NULL)
    )
);

CREATE TABLE minigame_best_scores (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    user_id       BIGINT      NOT NULL,
    game_code     VARCHAR(40) NOT NULL,
    rules_version INT         NOT NULL,
    score         INT         NOT NULL,
    achieved_at   DATETIME(6) NOT NULL,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_minigame_best_score_game_user UNIQUE (game_code, user_id),
    CONSTRAINT fk_minigame_best_score_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_minigame_best_score_rules_version CHECK (rules_version >= 1),
    CONSTRAINT ck_minigame_best_score_score CHECK (score >= 0)
);

CREATE INDEX idx_minigame_best_score_leaderboard
    ON minigame_best_scores (game_code, score DESC, achieved_at ASC, user_id ASC);
