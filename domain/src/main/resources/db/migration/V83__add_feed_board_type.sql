-- 기존 사진 피드는 인증게시판으로 분류하고 구버전 쓰기도 같은 기본값을 사용함.
ALTER TABLE feed_posts ADD COLUMN board_type VARCHAR(20) NOT NULL DEFAULT 'VERIFICATION';
CREATE INDEX idx_feed_post_board_page ON feed_posts (board_type, deleted_at, id);
CREATE INDEX idx_feed_post_board_author_page ON feed_posts (board_type, author_id, deleted_at, id);
