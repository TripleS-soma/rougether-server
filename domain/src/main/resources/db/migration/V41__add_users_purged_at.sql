-- 회원탈퇴 데이터 파기 배치(withdrawal purge) 완료 표식.
-- deleted_at IS NOT NULL AND purged_at IS NULL 인 유저가 파기 대상.
ALTER TABLE users ADD COLUMN purged_at TIMESTAMP NULL;

-- purge 가 지운 이미지 row(인증사진·버그리포트 이미지)의 S3 원본 파기 대기열.
-- 이미지 row 가 object key 의 유일한 매핑이라, row 삭제 전에 key 를 여기로 옮겨둬야
-- IAM delete 권한 정책 확정 후 후속 작업이 원본을 찾아 지울 수 있음.
-- 파기 대기열은 유저 lifecycle 과 독립이라 FK 를 걸지 않음.
CREATE TABLE purged_asset_keys (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    storage_key VARCHAR(255) NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);

-- purge 배치 대상 스캔(deleted_at IS NOT NULL AND ... AND purged_at IS NULL)용.
-- 대다수 row 가 deleted_at NULL 이라 이 인덱스로 탈퇴 유저만 좁혀 매시 풀스캔을 피함.
CREATE INDEX idx_users_deleted_at ON users (deleted_at);
