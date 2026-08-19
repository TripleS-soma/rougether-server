-- 기기 캘린더 반복 일정 임포트 중복 방지용 외부 참조 (spec #81, 서버 #317). external_id 는 캘린더 시리즈 id.
-- 일반 루틴은 둘 다 NULL — unique 는 NULL 다중 허용이라 영향 없음.
-- soft delete 된 행(스케줄 수정으로 닫힌 옛 버전 포함)도 unique 에 남으므로 같은 시리즈는 다시 임포트되지 않는다.
-- (admin-api 테스트의 H2 MySQL 모드가 다중 ADD COLUMN 을 못 읽어 문장을 나눈다)
ALTER TABLE routines ADD COLUMN external_source VARCHAR(30) NULL;
ALTER TABLE routines ADD COLUMN external_id VARCHAR(255) NULL;

ALTER TABLE routines ADD CONSTRAINT uk_routines_user_external UNIQUE (user_id, external_source, external_id);
