-- 발행 재고 판매 주문 수량 수정 접수(#433). 접수 대장 type CHECK 에 AMEND 를 더한다.
ALTER TABLE market_commands DROP CONSTRAINT ck_market_commands_type;
ALTER TABLE market_commands ADD CONSTRAINT ck_market_commands_type
    CHECK (type IN ('PLACE', 'CANCEL', 'EXPIRE', 'AMEND'));
