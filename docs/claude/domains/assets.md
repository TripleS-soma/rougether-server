# 에셋 / 이미지 / CDN (서버 구현 노트)

에셋 관련 계약의 **정본은 spec repo**에 분산되어 있습니다.

- 아이템/테마: [rougether-spec](https://github.com/TripleS-soma/rougether-spec) repo의 `domains/shop/`
- 방 배치: 같은 repo `domains/room/`
- 공통 규약(이미지 key 원칙): 같은 repo `api.md`

원칙: 전체 CDN URL이 아니라 `*_key`(`asset_key`, `cover_image_key`, `storage_key`)로 저장하고, base URL은 클라이언트/서버 설정에서 조합합니다.

이 문서는 이 서버 repo에서의 **구현 노트**(스토리지 연동, 업로드 흐름 등)만 둡니다.

## 구현 노트

- 캐릭터 애니메이션: `characters/{code}/animations/{idle|pose-cycle|wave}.png` (APNG) 규칙으로 S3 에 적재한다. API 는 이 key 를 DB 저장 없이 code 로 파생해 내려주므로(`CharacterAnimations.of`), **새 캐릭터를 카탈로그에 등록하기 전에 애니메이션 3종 적재가 전제 조건**이다 — 빠지면 프론트에서 해당 캐릭터 애니메이션이 404 가 된다. Android 의 APNG 재생 여부에 따라 포맷이 애니메이션 WebP 로 바뀔 수 있음(프론트 확인 대기, 2026-07-15).
