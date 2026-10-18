ALTER TABLE feed_comments ADD COLUMN parent_comment_id BIGINT NULL;
ALTER TABLE feed_comments ADD COLUMN reply_to_comment_id BIGINT NULL;
ALTER TABLE feed_comments ADD CONSTRAINT fk_feed_comment_parent
    FOREIGN KEY (parent_comment_id) REFERENCES feed_comments(id) ON DELETE CASCADE;
ALTER TABLE feed_comments ADD CONSTRAINT fk_feed_comment_reply_to
    FOREIGN KEY (reply_to_comment_id) REFERENCES feed_comments(id) ON DELETE SET NULL;
CREATE INDEX idx_feed_comment_replies ON feed_comments (post_id, parent_comment_id, deleted_at, id);
