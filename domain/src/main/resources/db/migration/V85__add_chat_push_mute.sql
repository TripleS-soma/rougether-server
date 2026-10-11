-- 집 채팅 방별 푸시 음소거(#435). 읽음 상태와 같은 (방, 사용자) 단위라 같은 행에 둔다. 기본 = 알림 받음.
ALTER TABLE chat_read_states ADD COLUMN push_muted BOOLEAN NOT NULL DEFAULT FALSE;
