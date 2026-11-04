-- 기존 예약은 생성권 1회로 정산하고, 신규 코인 결제는 실제 차감액으로 반환함.
ALTER TABLE furniture_credit_reservations
    ADD COLUMN payment_type VARCHAR(20) NOT NULL DEFAULT 'CREDIT';

ALTER TABLE furniture_credit_reservations
    ADD COLUMN payment_amount INT NOT NULL DEFAULT 1;
