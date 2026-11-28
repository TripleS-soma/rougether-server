-- 피드 글·댓글 자동 유해 판정(#478). 판정 결과를 대상당 1행으로 두고, 기준 이상이면 시스템 신고(source=AUTO)를 만든다.
-- 자동 신고는 신고자가 없으므로 reporter_user_id 를 NULL 허용으로 바꾸고 출처로 구분한다. 기존 행은 모두 사용자 신고다.
ALTER TABLE content_reports ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'USER';
ALTER TABLE content_reports MODIFY reporter_user_id BIGINT NULL;
ALTER TABLE content_reports ADD CONSTRAINT ck_content_reports_source CHECK (
    (source = 'USER' AND reporter_user_id IS NOT NULL) OR (source = 'AUTO' AND reporter_user_id IS NULL));

-- 판정 원문은 저장하지 않는다. content_hash 가 바뀐 글(수정)만 다시 판정한다.
CREATE TABLE content_screenings (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    target_type      VARCHAR(20)   NOT NULL,
    target_id        BIGINT        NOT NULL,
    content_hash     CHAR(64)      NOT NULL,
    model            VARCHAR(40)   NOT NULL,
    harm_probability DECIMAL(5, 4) NOT NULL,
    top_reason       VARCHAR(20)   NOT NULL,
    probabilities    JSON          NOT NULL,
    input_tokens     INT           NOT NULL,
    flagged          BOOLEAN       NOT NULL,
    report_id        BIGINT        NULL,
    screened_at      TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_content_screenings_target UNIQUE (target_type, target_id),
    CONSTRAINT fk_content_screenings_report FOREIGN KEY (report_id) REFERENCES content_reports (id) ON DELETE SET NULL,
    CONSTRAINT ck_content_screenings_target_type CHECK (target_type IN ('FEED_POST', 'FEED_COMMENT')),
    CONSTRAINT ck_content_screenings_reason CHECK (top_reason IN
        ('SPAM', 'ABUSE', 'SEXUAL', 'VIOLENCE', 'PERSONAL_INFO')),
    CONSTRAINT ck_content_screenings_probability CHECK (harm_probability BETWEEN 0 AND 1),
    CONSTRAINT ck_content_screenings_tokens CHECK (input_tokens >= 0)
);
