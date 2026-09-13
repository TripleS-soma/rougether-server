ALTER TABLE house ADD COLUMN onboarding_auto_join_enabled BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_house_onboarding_match ON house (onboarding_auto_join_enabled, is_public, deleted_at, id);

CREATE TABLE onboarding_house_selections (
    user_id BIGINT NOT NULL,
    choice VARCHAR(20) NOT NULL,
    result VARCHAR(20) NOT NULL,
    membership_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id),
    CONSTRAINT fk_onboarding_house_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_onboarding_house_member FOREIGN KEY (membership_id) REFERENCES house_members (id),
    CONSTRAINT ck_onboarding_house_choice CHECK (
        (choice = 'PERSONAL' AND result = 'PERSONAL') OR
        (choice = 'AUTO_JOIN' AND result IN ('JOINED', 'NO_MATCH'))
    )
);
