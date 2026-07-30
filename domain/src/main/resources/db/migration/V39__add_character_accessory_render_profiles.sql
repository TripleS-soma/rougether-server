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

-- 기존에 운영 카탈로그에 등록된 고양이 악세서리는 배포 직후 바로 장착할 수 있어야 한다.
-- 아직 카탈로그에 없는 항목은 관리 API seed로 등록할 때 같은 렌더 프로필을 함께 적재한다.
INSERT INTO character_accessory_render_profiles (
    item_id,
    character_id,
    render_state,
    asset_key,
    canvas_width,
    canvas_height,
    asset_width,
    asset_height,
    position_x,
    position_y,
    width_ratio,
    rotation_deg,
    z_index,
    created_at,
    updated_at
)
SELECT
    i.id,
    c.id,
    p.render_state,
    p.asset_key,
    p.canvas_width,
    p.canvas_height,
    p.asset_width,
    p.asset_height,
    p.position_x,
    p.position_y,
    p.width_ratio,
    p.rotation_deg,
    p.z_index,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM (
    SELECT 'items/character-accessories/headwear/cat-navy-wizard-hat/thumbnail.png' AS item_asset_key, 'cat' AS character_code, 'default' AS render_state, 'items/character-accessories/headwear/cat-navy-wizard-hat/thumbnail.png' AS asset_key, 180 AS canvas_width, 172 AS canvas_height, 320 AS asset_width, 160 AS asset_height, 0.45331 AS position_x, 0.14386 AS position_y, 0.4987 AS width_ratio, 0 AS rotation_deg, 10 AS z_index
    UNION ALL SELECT 'items/character-accessories/headwear/cat-white-chef-toque/thumbnail.png', 'cat', 'default', 'items/character-accessories/headwear/cat-white-chef-toque/thumbnail.png', 180, 172, 320, 160, 0.45482, 0.14211, 0.4877, 0, 10
    UNION ALL SELECT 'items/character-accessories/headwear/cat-blue-sailor-cap/thumbnail.png', 'cat', 'default', 'items/character-accessories/headwear/cat-blue-sailor-cap/thumbnail.png', 180, 172, 320, 160, 0.45482, 0.15965, 0.4530, 0, 10
    UNION ALL SELECT 'items/character-accessories/headwear/cat-striped-party-hat/thumbnail.png', 'cat', 'default', 'items/character-accessories/headwear/cat-striped-party-hat/thumbnail.png', 180, 172, 320, 160, 0.44880, 0.14035, 0.4775, 0, 10
    UNION ALL SELECT 'items/character-accessories/headwear/cat-yellow-construction-helmet/thumbnail.png', 'cat', 'default', 'items/character-accessories/headwear/cat-yellow-construction-helmet/thumbnail.png', 180, 172, 320, 160, 0.45030, 0.14035, 0.4844, 0, 10
    UNION ALL SELECT 'items/character-accessories/headwear/cat-green-frog-cap/thumbnail.png', 'cat', 'default', 'items/character-accessories/headwear/cat-green-frog-cap/thumbnail.png', 180, 172, 320, 160, 0.45633, 0.14035, 0.5000, 0, 10
    UNION ALL SELECT 'items/character-accessories/headwear/cat-pink-bow-headband/thumbnail.png', 'cat', 'default', 'items/character-accessories/headwear/cat-pink-bow-headband/thumbnail.png', 180, 172, 320, 160, 0.45181, 0.17193, 0.4337, 0, 10
    UNION ALL SELECT 'items/character-accessories/headwear/cat-spring-flower-crown/thumbnail.png', 'cat', 'default', 'items/character-accessories/headwear/cat-spring-flower-crown/thumbnail.png', 180, 172, 320, 160, 0.45331, 0.18421, 0.5060, 0, 10
    UNION ALL SELECT 'items/character-accessories/headwear/cat-red-santa-hat/thumbnail.png', 'cat', 'default', 'items/character-accessories/headwear/cat-red-santa-hat/thumbnail.png', 180, 172, 320, 160, 0.45181, 0.15263, 0.4627, 0, 10
    UNION ALL SELECT 'items/character-accessories/headwear/cat-brown-detective-cap/thumbnail.png', 'cat', 'default', 'items/character-accessories/headwear/cat-brown-detective-cap/thumbnail.png', 180, 172, 320, 160, 0.44880, 0.14912, 0.4627, 0, 10
    UNION ALL SELECT 'items/character-accessories/headwear/cat-black-top-hat/thumbnail.png', 'cat', 'default', 'items/character-accessories/headwear/cat-black-top-hat/thumbnail.png', 180, 172, 320, 160, 0.45181, 0.14561, 0.4186, 0, 10
    UNION ALL SELECT 'items/character-accessories/eyewear/cat-pink-heart-sunglasses/thumbnail.png', 'cat', 'default', 'items/character-accessories/eyewear/cat-pink-heart-sunglasses/thumbnail.png', 180, 172, 320, 160, 0.43373, 0.43167, 0.7422, 0, 20
    UNION ALL SELECT 'items/character-accessories/eyewear/cat-yellow-star-glasses/thumbnail.png', 'cat', 'default', 'items/character-accessories/eyewear/cat-yellow-star-glasses/thumbnail.png', 180, 172, 320, 160, 0.43223, 0.43518, 0.7470, 0, 20
    UNION ALL SELECT 'items/character-accessories/eyewear/cat-navy-sleep-mask/thumbnail.png', 'cat', 'default', 'items/character-accessories/eyewear/cat-navy-sleep-mask/thumbnail.png', 180, 172, 320, 160, 0.43373, 0.42991, 0.7325, 0, 20
    UNION ALL SELECT 'items/character-accessories/eyewear/cat-blue-square-glasses/thumbnail.png', 'cat', 'default', 'items/character-accessories/eyewear/cat-blue-square-glasses/thumbnail.png', 180, 172, 320, 160, 0.43373, 0.42991, 0.7422, 0, 20
    UNION ALL SELECT 'items/character-accessories/neckwear/cat-red-bow-tie/thumbnail.png', 'cat', 'default', 'items/character-accessories/neckwear/cat-red-bow-tie/thumbnail.png', 180, 172, 320, 160, 0.45482, 0.61228, 0.2795, 0, 15
    UNION ALL SELECT 'items/character-accessories/facewear/cat-curled-mustache/thumbnail.png', 'cat', 'default', 'items/character-accessories/facewear/cat-curled-mustache/thumbnail.png', 180, 172, 320, 160, 0.43373, 0.47807, 0.3373, 0, 25
    UNION ALL SELECT 'items/character-accessories/eyewear/cat-red-blue-3d-glasses/thumbnail.png', 'cat', 'default', 'items/character-accessories/eyewear/cat-red-blue-3d-glasses/thumbnail.png', 180, 172, 320, 160, 0.43675, 0.42991, 0.7518, 0, 20
    UNION ALL SELECT 'items/character-accessories/eyewear/cat-pixel-shades/thumbnail.png', 'cat', 'default', 'items/character-accessories/eyewear/cat-pixel-shades/thumbnail.png', 180, 172, 320, 160, 0.43524, 0.42991, 0.7470, 0, 20
    UNION ALL SELECT 'items/character-accessories/eyewear/cat-sunglasses/thumbnail.png', 'cat', 'default', 'items/character-accessories/eyewear/cat-sunglasses/thumbnail.png', 180, 172, 320, 160, 0.43223, 0.43167, 0.5351, 0, 20
    UNION ALL SELECT 'items/character-accessories/eyewear/cat-red-round-glasses/thumbnail.png', 'cat', 'default', 'items/character-accessories/eyewear/cat-red-round-glasses/thumbnail.png', 180, 172, 320, 160, 0.43223, 0.43342, 0.5563, 0, 20
    UNION ALL SELECT 'items/character-accessories/eyewear/cat-black-horn-rim-glasses/thumbnail.png', 'cat', 'default', 'items/character-accessories/eyewear/cat-black-horn-rim-glasses/thumbnail.png', 180, 172, 320, 160, 0.43825, 0.42991, 0.5913, 0, 20
    UNION ALL SELECT 'items/character-accessories/headwear/cat-golden-crown/thumbnail.png', 'cat', 'default', 'items/character-accessories/headwear/cat-golden-crown/thumbnail.png', 180, 172, 320, 160, 0.45482, 0.13860, 0.5124, 0, 10
    UNION ALL SELECT 'items/character-accessories/headwear/cat-tattered-wanderer-hat/thumbnail.png', 'cat', 'default', 'items/character-accessories/headwear/cat-tattered-wanderer-hat/thumbnail.png', 180, 172, 320, 160, 0.45030, 0.13509, 0.5122, 0, 10
    UNION ALL SELECT 'items/character-accessories/headwear/cat-red-beret/thumbnail.png', 'cat', 'default', 'items/character-accessories/headwear/cat-red-beret/thumbnail.png', 180, 172, 320, 160, 0.45482, 0.14386, 0.4723, 0, 10
) AS p
JOIN items AS i
    ON i.asset_key = p.item_asset_key
JOIN characters AS c
    ON c.code = p.character_code;
