-- 기기 캘린더(구글 캘린더 등) 일정 임포트 중복 방지용 외부 참조 (spec #80, 서버 #299).
-- 일반 투두는 둘 다 NULL — unique 는 NULL 다중 허용이라 영향 없음.
-- soft delete 된 행도 unique 에 포함되므로 사용자가 지운 임포트 투두는 재임포트되지 않는다.
-- (admin-api 테스트의 H2 MySQL 모드가 다중 ADD COLUMN 을 못 읽어 문장을 나눈다)
ALTER TABLE todos ADD COLUMN external_source VARCHAR(30) NULL;
ALTER TABLE todos ADD COLUMN external_id VARCHAR(255) NULL;

ALTER TABLE todos ADD CONSTRAINT uk_todos_user_external UNIQUE (user_id, external_source, external_id);
