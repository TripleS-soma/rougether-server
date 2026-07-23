ALTER TABLE items
    ADD COLUMN default_position_x DECIMAL(6, 5) NULL;

ALTER TABLE items
    ADD COLUMN default_position_y DECIMAL(6, 5) NULL;

ALTER TABLE items
    ADD CONSTRAINT chk_items_default_position_pair CHECK (
        (default_position_x IS NULL AND default_position_y IS NULL)
        OR (
            default_position_x IS NOT NULL
            AND default_position_y IS NOT NULL
            AND default_position_x BETWEEN 0 AND 1
            AND default_position_y BETWEEN 0 AND 1
        )
    );
