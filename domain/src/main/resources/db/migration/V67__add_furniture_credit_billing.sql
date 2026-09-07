-- 현금 결제 생성권은 기존 코인/다이아와 분리함. 유료 생성권은 만료시키지 않음.
CREATE TABLE furniture_credit_accounts (
    user_id BIGINT PRIMARY KEY,
    account_token VARCHAR(36) NOT NULL UNIQUE,
    balance BIGINT NOT NULL DEFAULT 0,
    reserved BIGINT NOT NULL DEFAULT 0,
    last_verification_at TIMESTAMP(6),
    CONSTRAINT fk_furniture_credit_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE furniture_credit_purchases (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    store VARCHAR(20) NOT NULL,
    environment VARCHAR(20) NOT NULL,
    reference_hash VARCHAR(64) NOT NULL,
    reference_encrypted VARCHAR(6000) NOT NULL,
    product_id VARCHAR(200) NOT NULL,
    credits_per_unit INT NOT NULL,
    quantity INT NOT NULL,
    granted_credits BIGINT NOT NULL DEFAULT 0,
    revoked_credits BIGINT NOT NULL DEFAULT 0,
    last_store_signed_at BIGINT,
    store_consumed BOOLEAN NOT NULL DEFAULT FALSE,
    next_consume_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_furniture_purchase_reference UNIQUE (store, environment, reference_hash),
    CONSTRAINT fk_furniture_purchase_user FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE INDEX idx_furniture_purchase_consume ON furniture_credit_purchases(store, store_consumed, next_consume_at);

CREATE TABLE furniture_credit_reservations (
    job_id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_furniture_credit_job FOREIGN KEY (job_id) REFERENCES furniture_generation_jobs(id),
    CONSTRAINT fk_furniture_reservation_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE furniture_credit_entries (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    reference_id VARCHAR(36) NOT NULL,
    reason VARCHAR(30) NOT NULL,
    amount BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_furniture_credit_entry_user FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE INDEX idx_furniture_credit_history ON furniture_credit_entries(user_id, created_at);
