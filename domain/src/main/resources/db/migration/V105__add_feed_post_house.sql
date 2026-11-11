-- 집 피드(#528): 글이 속한 집. NULL 이면 기존 공개 글이고 값이 있으면 그 집 ACTIVE 구성원만 조회함.
-- 기존 글은 NULL 로 남김(백필 없음).
-- FK 는 두지 않음(V84 routine_id 와 같은 방식). FK 가 있으면 글 등록이 집 행에 공유 락을 잡아
-- 집 참여 흐름(집 → 회원 잠금)과 교착할 수 있고, 추가할 때 테이블 전체를 재구성해 쓰기를 막음.
-- 집은 soft delete 만 하므로 없는 집을 가리키는 글은 등록 시 구성원 검사로 막음.
ALTER TABLE feed_posts ADD COLUMN house_id BIGINT NULL;
CREATE INDEX idx_feed_post_house_page ON feed_posts (house_id, id);
