-- 인증된 user-api 요청의 일별 최초 활동. 탈퇴 사용자와 동거 봇은 기록 서비스에서 제외한다.
CREATE TABLE user_daily_activity (
    id            BIGINT    NOT NULL AUTO_INCREMENT,
    user_id       BIGINT    NOT NULL,
    activity_date DATE      NOT NULL,
    created_at    TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_daily_activity_user_date UNIQUE (user_id, activity_date),
    CONSTRAINT fk_user_daily_activity_user FOREIGN KEY (user_id) REFERENCES users (id)
);

-- 날짜 코호트 집계에서 user_id까지 인덱스만 읽도록 지원함.
CREATE INDEX idx_user_daily_activity_date_user
    ON user_daily_activity (activity_date, user_id);

-- 완료 행동일(completed_at) 기반 북극성·재시작 집계용. 기존 (routine_id, routine_date) 인덱스와 역할이 다름.
CREATE INDEX idx_routine_logs_status_completed_at
    ON routine_logs (status, completed_at, routine_id);

-- 종료된 예정일 범위의 완료율 분모·분자 집계용. 전역 routine_date 범위 조회는 기존 routine 선두 인덱스를 못 탐.
CREATE INDEX idx_routine_logs_date_status_routine
    ON routine_logs (routine_date, status, routine_id);
