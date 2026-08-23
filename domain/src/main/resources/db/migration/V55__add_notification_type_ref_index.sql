-- 주간 회고 push 중복 판정(not exists)과 리마인드 당일 중복 판정이 notification 을 (type, ref_id) 로
-- 탐침하는데 지원 인덱스가 없어 최다 증가 테이블 풀 스캔이 된다(#330 리뷰). 두 경로 공용 인덱스 추가.
CREATE INDEX idx_notification_type_ref ON notification (type, ref_id);
