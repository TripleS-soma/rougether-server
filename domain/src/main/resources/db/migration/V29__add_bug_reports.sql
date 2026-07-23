-- 인앱 버그 제보 (#213). 유저 제출 + 어드민 처리 상태(RECEIVED/IN_PROGRESS/RESOLVED) 관리.
-- 스크린샷은 S3 bug-reports/ prefix 에 올리고 storage_key 만 저장한다(공통 규칙 - 전체 URL 저장 금지).

CREATE TABLE bug_reports (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    user_id     BIGINT        NOT NULL,
    title       VARCHAR(100)  NOT NULL,
    content     VARCHAR(2000) NOT NULL,
    app_version VARCHAR(30)   NULL,
    device_info VARCHAR(100)  NULL,
    status      VARCHAR(30)   NOT NULL,
    created_at  TIMESTAMP     NOT NULL,
    updated_at  TIMESTAMP     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_bugreport_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE bug_report_images (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    bug_report_id BIGINT       NOT NULL,
    storage_key   VARCHAR(255) NOT NULL,
    sort_order    INT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_bugreport_image_report FOREIGN KEY (bug_report_id) REFERENCES bug_reports (id)
);
