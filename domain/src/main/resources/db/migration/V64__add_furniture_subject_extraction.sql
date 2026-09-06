-- 사진의 식별 특징을 한 번만 추출해 생성/재생성에 재사용함. 원본과 함께 만료 시 제거함.
ALTER TABLE furniture_generation_jobs ADD COLUMN subject_json VARCHAR(2500);
ALTER TABLE furniture_generation_jobs ADD COLUMN extraction_attempts INT NOT NULL DEFAULT 0;
