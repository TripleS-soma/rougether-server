-- 인증게시판 글이 연결한 루틴 완료 기록(루틴 id·KST 완료 날짜)과 연결 시점 루틴 제목 스냅샷.
-- 기존 글은 연결 없이 NULL 로 남김(백필 없음). 루틴은 버전 분기·soft delete·탈퇴 정리로 행이 바뀌거나
-- 사라지므로 FK 를 두지 않고 식별자와 제목을 이력으로 보관함.
ALTER TABLE feed_posts ADD COLUMN routine_id BIGINT NULL;
ALTER TABLE feed_posts ADD COLUMN routine_date DATE NULL;
ALTER TABLE feed_posts ADD COLUMN routine_title VARCHAR(160) NULL;
