-- 캐릭터 악세사리 단품 이미지를 캐릭터 캔버스에 합성할 때 쓰는 카탈로그 렌더 프로필.
-- default 상태가 있으면 해당 캐릭터에 착용 가능하며, 포즈별 상태는 default 값을 선택적으로 덮어쓴다.
CREATE TABLE character_accessory_render_profiles (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    item_id        BIGINT        NOT NULL,
    character_id   BIGINT        NOT NULL,
    render_state   VARCHAR(40)   NOT NULL,
    asset_key      VARCHAR(255)  NOT NULL,
    canvas_width   INT           NOT NULL,
    canvas_height  INT           NOT NULL,
    asset_width    INT           NOT NULL,
    asset_height   INT           NOT NULL,
    position_x     DECIMAL(6,5)  NOT NULL,
    position_y     DECIMAL(6,5)  NOT NULL,
    width_ratio    DECIMAL(5,4)  NOT NULL,
    rotation_deg   INT           NOT NULL,
    z_index        INT           NOT NULL,
    created_at     TIMESTAMP     NOT NULL,
    updated_at     TIMESTAMP     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_accessory_render_profile
        UNIQUE (item_id, character_id, render_state),
    CONSTRAINT fk_accessory_render_profile_item
        FOREIGN KEY (item_id) REFERENCES items (id),
    CONSTRAINT fk_accessory_render_profile_character
        FOREIGN KEY (character_id) REFERENCES characters (id),
    CONSTRAINT chk_accessory_render_canvas_size
        CHECK (canvas_width > 0 AND canvas_height > 0),
    CONSTRAINT chk_accessory_render_asset_size
        CHECK (asset_width > 0 AND asset_height > 0),
    CONSTRAINT chk_accessory_render_position_x
        CHECK (position_x >= 0 AND position_x <= 1),
    CONSTRAINT chk_accessory_render_position_y
        CHECK (position_y >= 0 AND position_y <= 1),
    CONSTRAINT chk_accessory_render_width_ratio
        CHECK (width_ratio > 0 AND width_ratio <= 2),
    CONSTRAINT chk_accessory_render_rotation
        CHECK (rotation_deg >= -360 AND rotation_deg <= 360)
);

CREATE INDEX idx_accessory_render_character_item
    ON character_accessory_render_profiles (character_id, item_id);
