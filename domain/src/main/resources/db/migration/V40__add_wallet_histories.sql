-- 재화 증감 원장(#253).
-- 적립·차감을 모두 한 테이블에 기록한다. 지급액 0 인 이벤트는 기록하지 않는다.
-- 루틴/투두 완료 취소는 회수 row 를 남기지 않고 원 획득 row 를 삭제한다(source_type/source_id 로 특정).
-- balance_after 는 증감 직후 잔액 스냅샷(지갑 갱신과 같은 트랜잭션에서 기록).
CREATE TABLE wallet_histories (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    user_id       BIGINT      NOT NULL,
    currency_type VARCHAR(30) NOT NULL,
    amount        INT         NOT NULL,
    reason        VARCHAR(30) NOT NULL,
    balance_after INT         NOT NULL,
    source_type   VARCHAR(30) NULL,
    source_id     BIGINT      NULL,
    created_at    TIMESTAMP   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_wallet_histories_user FOREIGN KEY (user_id) REFERENCES users (id)
);

-- 내 이력 조회(재화별·최신순)용.
CREATE INDEX idx_wallet_histories_user ON wallet_histories (user_id, currency_type, created_at);

-- 완료 취소 시 원 획득 row 특정용.
CREATE INDEX idx_wallet_histories_source ON wallet_histories (source_type, source_id);
