-- 회원탈퇴 데이터 파기 배치(withdrawal purge) 완료 표식.
-- deleted_at IS NOT NULL AND purged_at IS NULL 인 유저가 파기 대상.
ALTER TABLE users ADD COLUMN purged_at TIMESTAMP NULL;
