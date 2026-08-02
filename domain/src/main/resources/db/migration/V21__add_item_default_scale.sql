-- 아이템별 기본 렌더링 배율. 기존·신규 아이템은 1.00을 기본값으로 사용한다.
ALTER TABLE items ADD COLUMN default_scale DECIMAL(4,2) NOT NULL DEFAULT 1.00;
