-- 알림 push 발송 상태 추적. 기존 행은 이미 발송 시도가 끝난 상태이므로 SENT로 백필.
-- DB: MySQL 8 (운영) / H2 MySQL 모드 (로컬·테스트) 양립.

ALTER TABLE notification ADD COLUMN push_status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
UPDATE notification SET push_status = 'SENT';
