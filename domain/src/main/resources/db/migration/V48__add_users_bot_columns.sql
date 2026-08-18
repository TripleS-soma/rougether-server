-- 규칙 기반 동거 봇 계정(#307). 서버가 시드하는 로그인 불가 계정을 일반 회원과 구분한다.
-- is_bot  : 관측·보상·알림·배치·회고 격리 조건(#308)과 응답 표시(#309)의 기준.
-- bot_key : 코드 프로필 카탈로그(BotProfileCatalog)와 1:1 매핑되는 시드 멱등 키. 일반 회원은 NULL.
-- (ALTER 를 한 문장으로 합치지 않는다 — admin-api 테스트의 H2(MODE=MySQL)가 다중 ADD COLUMN 을 파싱하지 못함.)
ALTER TABLE users ADD COLUMN is_bot BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN bot_key VARCHAR(40) NULL;

CREATE UNIQUE INDEX uk_users_bot_key ON users (bot_key);
