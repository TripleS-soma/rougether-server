-- 기존 집과 새 집은 리디자인 집을 기본으로 제공한다.
ALTER TABLE house ADD COLUMN display_mode VARCHAR(16) NOT NULL DEFAULT 'DIORAMA';
