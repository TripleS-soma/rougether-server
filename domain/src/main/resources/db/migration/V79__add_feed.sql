CREATE TABLE feed_posts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    author_id BIGINT NOT NULL,
    client_post_id VARCHAR(36) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    deleted_at TIMESTAMP(6),
    CONSTRAINT uk_feed_post_retry UNIQUE (author_id, client_post_id),
    CONSTRAINT fk_feed_post_author FOREIGN KEY (author_id) REFERENCES users(id),
    INDEX idx_feed_post_page (deleted_at, id),
    INDEX idx_feed_post_author_page (author_id, deleted_at, id)
);
CREATE TABLE feed_images (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    post_id BIGINT,
    storage_key VARCHAR(255) NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    ready BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INT,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_feed_image_key UNIQUE (storage_key),
    CONSTRAINT fk_feed_image_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    CONSTRAINT fk_feed_image_post FOREIGN KEY (post_id) REFERENCES feed_posts(id),
    INDEX idx_feed_image_post (post_id, sort_order),
    INDEX idx_feed_image_expiry (expires_at, id)
);
CREATE TABLE feed_likes (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    CONSTRAINT uk_feed_like UNIQUE (post_id, user_id),
    CONSTRAINT fk_feed_like_post FOREIGN KEY (post_id) REFERENCES feed_posts(id),
    CONSTRAINT fk_feed_like_user FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE TABLE feed_comments (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    post_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    client_comment_id VARCHAR(36) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    content VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    deleted_at TIMESTAMP(6),
    CONSTRAINT uk_feed_comment_retry UNIQUE (post_id, author_id, client_comment_id),
    CONSTRAINT fk_feed_comment_post FOREIGN KEY (post_id) REFERENCES feed_posts(id),
    CONSTRAINT fk_feed_comment_author FOREIGN KEY (author_id) REFERENCES users(id),
    INDEX idx_feed_comment_page (post_id, deleted_at, id)
);
