-- 집 피드 새 글 푸시의 집별 설정(#530). 구성원이 집마다 자유글·인증글 푸시를 따로 켜고 끔.
-- 행이 없으면 기본값(자유글 켜짐, 인증글 꺼짐)으로 봄. 집을 나가도 행을 남겨 재입주하면 그대로 씀.
-- feed_posts.house_id 와 같은 방식으로 집·회원 FK 는 두지 않음 — 설정 저장이 집·회원 행에 공유 락을 잡지 않게 하고,
-- 집은 soft delete·회원 행은 탈퇴 뒤에도 남아 참조가 깨지지 않음.
CREATE TABLE house_feed_notification_settings (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    house_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    free_post_push BOOLEAN NOT NULL DEFAULT TRUE,
    verification_post_push BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_house_feed_notification UNIQUE (house_id, user_id)
);
