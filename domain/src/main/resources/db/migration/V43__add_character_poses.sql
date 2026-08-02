-- 캐릭터별로 프론트가 선택해 재생할 수 있는 포즈 에셋 카탈로그.
-- 표준 animations(idle/pose-cycle/wave)는 하위 호환을 위해 기존 key 규칙을 유지하고,
-- 추가 포즈만 이 테이블에서 동적으로 관리한다.
CREATE TABLE character_poses (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    character_id BIGINT       NOT NULL,
    code         VARCHAR(40)  NOT NULL,
    asset_key    VARCHAR(255) NOT NULL,
    sort_order   INT          NOT NULL,
    is_active    BOOLEAN      NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_character_pose_code
        UNIQUE (character_id, code),
    CONSTRAINT fk_character_pose_character
        FOREIGN KEY (character_id) REFERENCES characters (id)
);

CREATE INDEX idx_character_pose_active_sort
    ON character_poses (character_id, is_active, sort_order);

-- 현재 S3에 업로드된 고양이 포즈 4종을 배포와 함께 등록한다.
INSERT INTO character_poses (
    character_id,
    code,
    asset_key,
    sort_order,
    is_active,
    created_at,
    updated_at
)
SELECT
    c.id,
    p.code,
    p.asset_key,
    p.sort_order,
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM (
    SELECT 'temp1' AS code, 'characters/1f9c6b56-9c30-4554-b1ce-0be83642e627.webp' AS asset_key, 10 AS sort_order
    UNION ALL SELECT 'temp2', 'characters/3b1f16c8-7772-4c40-8065-0e3baf1a6f21.webp', 20
    UNION ALL SELECT 'temp3', 'characters/46c58f8f-5495-491e-a7be-095ba5eb4e95.webp', 30
    UNION ALL SELECT 'temp4', 'characters/4eb35995-6b9a-4baa-863b-7f1914dc6365.webp', 40
) AS p
JOIN characters AS c
    ON c.code = 'cat';
