-- 버그 제보 답장(#348). 운영자 답장 N개 append-only - 유저 재답장(양방향 스레드)은 후속.
-- FK 컬럼 인덱스는 InnoDB 가 FK 제약으로 자동 생성하므로 별도로 만들지 않는다.
CREATE TABLE bug_report_replies
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    bug_report_id BIGINT        NOT NULL,
    admin_user_id BIGINT        NOT NULL,
    content       VARCHAR(2000) NOT NULL,
    created_at    TIMESTAMP     NOT NULL,
    CONSTRAINT fk_bug_report_replies_report FOREIGN KEY (bug_report_id) REFERENCES bug_reports (id),
    CONSTRAINT fk_bug_report_replies_admin FOREIGN KEY (admin_user_id) REFERENCES admin_users (id)
);
