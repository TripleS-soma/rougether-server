-- 알림함 개별·전체 삭제(soft delete). 하드 삭제 대신 deleted_at 을 찍는 이유:
-- daily_incomplete_digests.notification_id FK 가 행을 참조하고, 주간 회고(not exists type+ref_id)·리마인드 당일
-- dedup·입주 신청 본문 dedup 이 notification 행 존재로 판정돼 행이 사라지면 같은 알림이 재발송됨.
-- 목록·전체 읽음 만 deleted_at IS NULL 을 보고, 발송·중복 판정 경로는 삭제 여부를 보지 않음(삭제된 PENDING 은
-- 발송 대신 BLOCKED 종결). 회원탈퇴는 기존대로 하드 삭제(deleteAllByUserId).
-- DB: MySQL 8 (운영·user-api/batch 테스트 Testcontainers) / H2 MySQL 모드 (admin-api 로컬) 양립.
ALTER TABLE notification ADD COLUMN deleted_at TIMESTAMP NULL;

-- 목록 쿼리(user_id = ? AND deleted_at IS NULL AND id < ? ORDER BY id DESC) 지원 인덱스. 기존 (user_id, id) 만으로는
-- 전체 삭제한 사용자의 목록 조회가 매 요청 그 사용자의 전 이력을 훑고 0건을 돌려주므로 deleted_at 을 끼워 넣음.
-- 기존 idx_notification_user_id 는 FK 지원·탈퇴 삭제 등 다른 경로가 쓰므로 유지.
CREATE INDEX idx_notification_user_deleted_id ON notification (user_id, deleted_at, id);
