-- 재화 증감 원장(#253).
-- 적립·차감을 모두 한 테이블에 기록한다. 지급액 0 인 이벤트는 기록하지 않는다.
-- 루틴/투두 완료 취소는 회수 row 를 남기지 않고 원 획득 row 를 삭제한다(user_id+source_type/source_id 로 특정).
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

-- 내 이력 조회용. 정렬 키가 id desc(최신순, created_at 동치)라 created_at 대신 id 를 둔다.
-- 재화 필터는 사용자당 row 수가 작아 인덱스 없이 걸러도 충분하다.
CREATE INDEX idx_wallet_histories_user ON wallet_histories (user_id, id);

-- 완료 취소 시 원 획득 row 특정용.
CREATE INDEX idx_wallet_histories_source ON wallet_histories (source_type, source_id);
