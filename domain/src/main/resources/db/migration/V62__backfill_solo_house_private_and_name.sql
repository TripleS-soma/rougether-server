-- 혼자 있는 집 백필(#350). 기본 집 비공개 생성(8/20)이 백필 없이 들어가 그 전 가입자의 공개
-- "나의 집"이 탐색을 도배함. 대상 = 실사용자(봇 제외) ACTIVE 구성원이 1명 이하인 집(봇만 있는 집 포함).
-- 되돌리기는 새 공개 전환 설정(isPublic)으로 소유자가 직접 한다.

-- 1) 이름이 기본값 그대로면 소유자 닉네임의 집으로 개명(닉네임 없으면 유지). 직접 지은 이름은 불변.
UPDATE house
SET name = CONCAT((SELECT u.nickname FROM users u WHERE u.id = house.owner_user_id), '의 집')
WHERE deleted_at IS NULL
  AND name = '나의 집'
  AND (SELECT u2.nickname FROM users u2 WHERE u2.id = house.owner_user_id) IS NOT NULL
  AND (SELECT COUNT(*) FROM house_members hm JOIN users hu ON hu.id = hm.user_id
       WHERE hm.house_id = house.id AND hm.status = 'ACTIVE' AND hu.is_bot = FALSE) <= 1;

-- 2) 공개로 남은 솔로 집 전부 비공개 전환.
UPDATE house
SET is_public = FALSE
WHERE deleted_at IS NULL
  AND is_public = TRUE
  AND (SELECT COUNT(*) FROM house_members hm JOIN users hu ON hu.id = hm.user_id
       WHERE hm.house_id = house.id AND hm.status = 'ACTIVE' AND hu.is_bot = FALSE) <= 1;
