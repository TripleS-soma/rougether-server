CREATE TABLE furniture_worker_capacity (
    id BIGINT PRIMARY KEY,
    max_in_flight INT NOT NULL,
    execution_enabled BOOLEAN NOT NULL,
    CONSTRAINT chk_furniture_worker_capacity CHECK (id = 1 AND max_in_flight BETWEEN 1 AND 100)
);
-- 최초 전환은 전체 1개로 시작함. 실측 및 호스트 예산 확인 후 명시적으로 변경함.
INSERT INTO furniture_worker_capacity VALUES (1, 1, TRUE);
