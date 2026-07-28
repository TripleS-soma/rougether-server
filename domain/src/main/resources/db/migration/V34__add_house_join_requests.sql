CREATE TABLE house_join_requests (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    house_id     BIGINT      NOT NULL,
    user_id      BIGINT      NOT NULL,
    status       VARCHAR(30) NOT NULL,
    requested_at TIMESTAMP   NOT NULL,
    processed_at TIMESTAMP   NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_house_join_request UNIQUE (house_id, user_id),
    CONSTRAINT fk_house_join_request_house FOREIGN KEY (house_id) REFERENCES house (id),
    CONSTRAINT fk_house_join_request_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_house_join_request_pending
    ON house_join_requests (house_id, status, requested_at);
