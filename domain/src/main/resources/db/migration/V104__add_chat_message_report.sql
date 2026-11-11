-- 채팅 메시지 신고와 운영자 삭제. 삭제해도 기록의 sequence를 유지하도록 소프트 삭제함.
ALTER TABLE chat_messages ADD COLUMN deleted_at TIMESTAMP(6) NULL;

ALTER TABLE content_reports DROP CONSTRAINT ck_content_reports_target_type;
ALTER TABLE content_reports ADD CONSTRAINT ck_content_reports_target_type
    CHECK (target_type IN ('FEED_POST', 'FEED_COMMENT', 'MARKET_ASSET', 'CHAT_MESSAGE'));
