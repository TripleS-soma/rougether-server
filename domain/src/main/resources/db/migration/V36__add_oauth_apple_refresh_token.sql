-- 회원탈퇴 시 애플 연동 해제(revoke) 호출에 쓸 refresh token(암호화) 저장 컬럼.
ALTER TABLE oauth_accounts ADD COLUMN apple_refresh_token_encrypted VARCHAR(1000) NULL AFTER provider_user_id;
