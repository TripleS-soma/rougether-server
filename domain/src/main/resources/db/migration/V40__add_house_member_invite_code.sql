-- 구성원 개인 초대코드 - 방장이 아닌 구성원도 초대할 수 있게 한다.
-- 이 코드로 참여하면 즉시가입 대신 house_join_requests PENDING 신청이 생성되고 방장 승인으로 확정된다.
ALTER TABLE house_members ADD COLUMN invite_code VARCHAR(50) NULL;
ALTER TABLE house_members ADD COLUMN invite_expires_at TIMESTAMP NULL;

-- 초대코드 고유성(중복 발급 시 findByInviteCode가 엉뚱한 구성원 매칭). invite_code는 nullable이라 NULL 다중은 허용됨.
ALTER TABLE house_members ADD CONSTRAINT uq_house_member_invite UNIQUE (invite_code);
