ALTER TABLE themes ADD CONSTRAINT uq_themes_code UNIQUE (code);
ALTER TABLE characters ADD CONSTRAINT uq_characters_code UNIQUE (code);
