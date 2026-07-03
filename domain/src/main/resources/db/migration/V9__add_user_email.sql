-- 소셜 로그인 시 provider가 제공/동의한 이메일을 users에 저장(카카오 우선).
-- nullable: 이메일 미동의/미제공 회원 허용. unique 없음: 다른 provider로 같은 이메일 재연결 여지 남김.

ALTER TABLE users ADD COLUMN email VARCHAR(255) NULL;
