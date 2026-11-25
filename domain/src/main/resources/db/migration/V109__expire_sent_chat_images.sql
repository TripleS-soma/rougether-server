-- 전송 사진은 전송 시각부터 30일만 열람함. 파일 정리 후에도 멱등성/만료 표시용 메타데이터를 유지함.
ALTER TABLE chat_images ADD COLUMN purged_at TIMESTAMP(6) NULL;

-- 기존 전송 사진의 만료 값(업로드+24시간)을 실제 전송 시각으로 보정함.
UPDATE chat_images SET expires_at = (
    SELECT TIMESTAMPADD(DAY, 30, m.created_at) FROM chat_messages m WHERE m.id = chat_images.message_id
) WHERE message_id IS NOT NULL;
