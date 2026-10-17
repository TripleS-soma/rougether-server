-- SNS 방문 예약과 실제 게시 이력을 분리함. 대기는 작업의 사유만 기록함.
ALTER TABLE ai_user_profiles ADD COLUMN next_social_at TIMESTAMP(6) NULL;
ALTER TABLE ai_user_jobs ADD COLUMN wait_reason VARCHAR(40) NULL;
CREATE TABLE ai_user_social_actions (
 job_id BIGINT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 action_name VARCHAR(40) NOT NULL,
 post_id BIGINT NOT NULL,
 proactive BOOLEAN NOT NULL,
 proactive_post_id BIGINT NULL,
 proactive_content_hash CHAR(64) NULL,
 topic VARCHAR(120) NULL,
 created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_ai_social_job FOREIGN KEY (job_id) REFERENCES ai_user_jobs(id) ON DELETE CASCADE,
 CONSTRAINT uk_ai_social_first_comment UNIQUE (user_id, proactive_post_id),
 CONSTRAINT uk_ai_social_content UNIQUE (user_id, proactive_content_hash)
);
CREATE INDEX ix_ai_social_user_date ON ai_user_social_actions(user_id,created_at,action_name);
CREATE INDEX ix_ai_social_post ON ai_user_social_actions(post_id,proactive,action_name);
