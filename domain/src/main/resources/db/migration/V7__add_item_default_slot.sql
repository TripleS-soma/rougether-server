-- positioned 가구의 기본 배치 슬롯(topLeft…bottomRight). 서버가 슬롯을 관리하고 admin 에서 조정한다.
-- surface 아이템(wallpaper/floor/background)은 default_slot 이 null.
ALTER TABLE items ADD COLUMN default_slot VARCHAR(40) NULL;
