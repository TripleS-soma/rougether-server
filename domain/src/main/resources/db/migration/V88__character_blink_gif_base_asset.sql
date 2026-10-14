-- 캐릭터 대표 이미지(base_asset_key)를 눈 깜빡임 GIF 로 교체(제품 결정 2026-10-14). 고양이는 이미 blink.gif 라 제외.
-- 대상은 S3(에셋 CDN)에 characters/{code}/animations/blink.gif 가 있음을 2026-10-14 에 확인한 7종으로 고정함.
-- 모루는 blink.gif 가 없고(V87 에서 비활성화) 기존 이미지를 유지함. 새 캐릭터는 이 목록에 넣지 말고 적재 시 key 를 직접 지정할 것.
-- 다시 실행해도 같은 값으로 덮어쓸 뿐이라 안전함. 캐릭터 행이 없는 환경(테스트 DB)에서는 아무 행도 바꾸지 않음.
UPDATE characters
SET base_asset_key = CONCAT('characters/', code, '/animations/blink.gif')
WHERE code IN ('dog', 'tiger', 'panda', 'bear', 'sheep', 'horse', 'otter');
