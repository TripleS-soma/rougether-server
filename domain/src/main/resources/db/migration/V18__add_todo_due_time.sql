-- 투두 마감일(due_date)에 시각을 함께 등록/조회할 수 있도록 due_time 컬럼을 추가한다.
-- 루틴의 scheduled_time과 동일하게 분 단위(초/나노 0)로 정규화해 저장한다.

ALTER TABLE todos ADD COLUMN due_time TIME NULL AFTER due_date;
