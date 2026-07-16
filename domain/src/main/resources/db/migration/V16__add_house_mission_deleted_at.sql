-- 단체미션 삭제(소유자 전용)를 soft delete 로 지원한다.
-- 기여 기록(house_mission_participants)은 남겨 이력을 보존하고, 조회 경로에서 삭제 미션과 함께 숨긴다.
-- 보상 수령(COMPLETED) 미션은 성장 포인트가 이미 지급돼 삭제 불가 정책 — 컬럼과 무관하게 서비스에서 거부.

ALTER TABLE house_missions ADD COLUMN deleted_at TIMESTAMP NULL;
