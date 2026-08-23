-- AI 회고 열람 추적(#332). 상세 최초 조회 시각 - 도달(push) 대비 열람 전환율의 분자.
-- 재조회는 덮어쓰지 않으며(첫 열람만 의미), 미열람은 NULL 로 남는다.
ALTER TABLE weekly_reports ADD COLUMN viewed_at TIMESTAMP NULL;
