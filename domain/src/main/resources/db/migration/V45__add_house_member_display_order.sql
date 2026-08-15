ALTER TABLE house_members
    ADD COLUMN display_order INT NOT NULL DEFAULT 2147483647;

CREATE INDEX idx_house_members_user_status_order
    ON house_members (user_id, status, display_order);
