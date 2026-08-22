-- AI 조정 추천(#329). 주간 배치가 실패 패턴 룰로 기존 루틴의 반복 스케줄 조정(ADJUST_DAYS)을 제안하고,
-- 적용은 사용자 수락으로만 일어난다. origin_routine_id 는 대상 루틴 계보 루트, routine_id 는 생성 시점의
-- 대상 버전(수락 시 계보 현재 버전과 불일치하면 stale 거부), applied_routine_id 는 수락 적용으로 분기된 새 버전.
CREATE TABLE routine_recommendations (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    user_id            BIGINT       NOT NULL,
    origin_routine_id  BIGINT       NOT NULL,
    routine_id         BIGINT       NOT NULL,
    rec_type           VARCHAR(30)  NOT NULL,
    source             VARCHAR(30)  NOT NULL,
    proposal           JSON         NOT NULL,
    message            VARCHAR(300) NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    expires_at         TIMESTAMP    NOT NULL,
    acted_at           TIMESTAMP    NULL,
    applied_routine_id BIGINT       NULL,
    created_at         TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_routine_recommendations_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_routine_recommendations_origin FOREIGN KEY (origin_routine_id) REFERENCES routines (id),
    CONSTRAINT fk_routine_recommendations_routine FOREIGN KEY (routine_id) REFERENCES routines (id),
    CONSTRAINT fk_routine_recommendations_applied FOREIGN KEY (applied_routine_id) REFERENCES routines (id)
);

-- 활성 목록 조회(user_id + status + expires_at)와 계보 쿨다운 판정(origin_routine_id + created_at)용
CREATE INDEX idx_routine_recommendations_user_status ON routine_recommendations (user_id, status, expires_at);
CREATE INDEX idx_routine_recommendations_origin_created ON routine_recommendations (origin_routine_id, created_at);
