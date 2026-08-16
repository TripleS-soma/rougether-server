-- AI 주간 회고(#286). 배치가 매주 일요일 00:30 KST에 직전 일~토 주를 집계·LLM 요약해 사용자당 1건 저장한다.
CREATE TABLE weekly_reports (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    user_id          BIGINT       NOT NULL,
    week_start_date  DATE         NOT NULL,
    week_end_date    DATE         NOT NULL,
    status           VARCHAR(30)  NOT NULL,
    model            VARCHAR(100) NULL,
    stats_json       JSON         NOT NULL,
    summary          VARCHAR(600) NOT NULL,
    sections_json    JSON         NOT NULL,
    generated_at     TIMESTAMP    NOT NULL,
    created_at       TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_weekly_reports_user_week UNIQUE (user_id, week_start_date),
    CONSTRAINT fk_weekly_reports_user FOREIGN KEY (user_id) REFERENCES users (id)
);
