-- 신고·차단(#399). App Store 1.2 사용자 생성 콘텐츠 요건: 신고, 사용자 차단, 운영자 조치.

-- 1) 콘텐츠 신고. 대상은 종류별 테이블을 가리키는 논리 참조라 FK 를 두지 않는다.
--    target_owner_user_id 는 신고 시점의 작성자·제작자 스냅샷(FK 없음). 신고자 탈퇴 시 신고 row 를 지운다.
CREATE TABLE content_reports (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    reporter_user_id     BIGINT       NOT NULL,
    target_type          VARCHAR(20)  NOT NULL,
    target_id            BIGINT       NOT NULL,
    target_owner_user_id BIGINT       NULL,
    reason               VARCHAR(20)  NOT NULL,
    detail               VARCHAR(500) NULL,
    status               VARCHAR(10)  NOT NULL DEFAULT 'RECEIVED',
    created_at           TIMESTAMP(6) NOT NULL,
    resolved_at          TIMESTAMP(6) NULL,
    resolved_by          BIGINT       NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_content_reports_reporter_target UNIQUE (reporter_user_id, target_type, target_id),
    CONSTRAINT fk_content_reports_reporter FOREIGN KEY (reporter_user_id) REFERENCES users (id),
    CONSTRAINT fk_content_reports_resolver FOREIGN KEY (resolved_by) REFERENCES admin_users (id),
    CONSTRAINT ck_content_reports_target_type CHECK (target_type IN ('FEED_POST', 'FEED_COMMENT', 'MARKET_ASSET')),
    CONSTRAINT ck_content_reports_reason CHECK (reason IN
        ('SPAM', 'ABUSE', 'SEXUAL', 'VIOLENCE', 'PERSONAL_INFO', 'COPYRIGHT', 'OTHER')),
    CONSTRAINT ck_content_reports_status CHECK (status IN ('RECEIVED', 'ACTIONED', 'DISMISSED'))
);
-- 운영자 대기열(상태별 최신순)과 같은 대상의 대기 신고 일괄 처리용.
CREATE INDEX idx_content_reports_status ON content_reports (status, id);
CREATE INDEX idx_content_reports_target ON content_reports (target_type, target_id, status);

-- 2) 한 방향 사용자 차단. 조회 쿼리가 (blocker, blocked) unique 로 NOT EXISTS 판정한다.
--    id 는 내 차단 목록의 커서. 회원탈퇴 시 양쪽 방향 row 를 지운다.
CREATE TABLE user_blocks (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    blocker_user_id BIGINT       NOT NULL,
    blocked_user_id BIGINT       NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_blocks_pair UNIQUE (blocker_user_id, blocked_user_id),
    CONSTRAINT fk_user_blocks_blocker FOREIGN KEY (blocker_user_id) REFERENCES users (id),
    CONSTRAINT fk_user_blocks_blocked FOREIGN KEY (blocked_user_id) REFERENCES users (id),
    CONSTRAINT ck_user_blocks_not_self CHECK (blocker_user_id <> blocked_user_id)
);
CREATE INDEX idx_user_blocks_blocked ON user_blocks (blocked_user_id);
