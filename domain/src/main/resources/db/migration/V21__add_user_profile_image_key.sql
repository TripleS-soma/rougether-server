-- 프로필 사진 S3 object key (전체 URL 아닌 key 저장, null = 기본 이미지)
ALTER TABLE users
ADD COLUMN profile_image_key VARCHAR(255) AFTER bio;
