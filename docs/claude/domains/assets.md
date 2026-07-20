# 에셋 / 이미지 / CDN (서버 구현 노트)

에셋 관련 계약의 **정본은 spec repo**에 분산되어 있습니다.

- 아이템/테마: [rougether-spec](https://github.com/TripleS-soma/rougether-spec) repo의 `domains/shop/`
- 방 배치: 같은 repo `domains/room/`
- 공통 규약(이미지 key 원칙): 같은 repo `api.md`

원칙: 전체 CDN URL이 아니라 `*_key`(`asset_key`, `cover_image_key`, `storage_key`)로 저장하고, base URL은 클라이언트/서버 설정에서 조합합니다.

이 문서는 이 서버 repo에서의 **구현 노트**(스토리지 연동, 업로드 흐름 등)만 둡니다.

## 구현 노트

- 캐릭터 애니메이션: `characters/{code}/animations/{idle|pose-cycle|wave}.webp` (애니메이션 WebP) 규칙으로 S3 에 적재한다. API 는 이 key 를 DB 저장 없이 code 로 파생해 내려주므로(`CharacterAnimations.of`), **새 캐릭터를 카탈로그에 등록하기 전에 애니메이션 3종 적재가 전제 조건**이다 — 빠지면 프론트에서 해당 캐릭터 애니메이션이 404 가 된다. 포맷은 애니메이션 WebP 로 확정(2026-07-15) — APNG 는 RN Android 미재생이라 전환했고, 원본 APNG(.png)는 S3 에 보존돼 있다. 신규 제작 시 APNG 로 만들어도 Pillow 로 일괄 변환해 .webp 로 적재한다.

## RoutineVillage Figma 에셋 제작

RoutineVillage의 가구·소품 이미지를 새로 만들거나 Figma 에셋 그룹에 추가할 때는
[`.claude/skills/routinevillage-asset-add/SKILL.md`](../../../.claude/skills/routinevillage-asset-add/SKILL.md)를 정본으로 사용한다.
`/routinevillage-asset-add <에셋 설명>`으로 호출하며, 기존 에셋의 실제 색상 추출부터 이미지 생성, 투명 PNG 정리,
Figma 삽입과 잘림 검증까지 수행한다.

이 워크플로의 핵심 규칙은 다음과 같다.

- 생성 전에 가장 가까운 기존 에셋을 찾아 실제 RGB/hex 팔레트를 추출한다.
- 단일 2D 스티커형 오브젝트만 생성하고, 관계없는 소품이나 배경을 임의로 추가하지 않는다.
- 크로마키 제거 후 투명 여백을 남기고, Figma image fill은 `FILL`이 아니라 `FIT`을 사용한다.
- 붙여넣은 레이어는 페이지 루트에 남기지 않고 올바른 에셋 그룹으로 이동한다.
- 완료 전 Assets 프레임을 캡처해 외곽선, 색상, 가장자리 잘림을 기존 가구 옆에서 비교한다.

### Figma 제작과 서버 등록의 경계

`routinevillage-asset-add`는 **Figma 에셋 라이브러리에 시각 자산을 추가하는 단계까지만** 담당한다.
완성된 이미지를 실제 앱에서 사용하려면 별도로 다음 서버 등록 절차를 수행해야 한다.

1. admin asset API 또는 `tools/admin-asset-mcp`의 `upload_asset`으로 오브젝트 스토리지에 업로드한다.
2. 반환된 전체 URL이 아니라 asset key를 확인한다.
3. 아이템·테마 카탈로그가 필요하면 `import_catalog`로 등록한다.
4. 기본 슬롯 배치가 필요하면 `import_default_slots`를 사용한다.
5. admin 조회 API에서 업로드 결과와 metadata를 다시 확인한다.

Figma에 들어갔다는 사실만으로 S3 업로드나 DB 카탈로그 등록이 끝났다고 판단하지 않는다.
