# 에셋 / 이미지 / CDN (서버 구현 노트)

에셋 관련 계약의 **정본은 spec repo**에 분산되어 있습니다.

- 아이템/테마: [rougether-spec](https://github.com/TripleS-soma/rougether-spec) repo의 `domains/shop/`
- 방 배치: 같은 repo `domains/room/`
- 공통 규약(이미지 key 원칙): 같은 repo `api.md`

원칙: 전체 CDN URL이 아니라 `*_key`(`asset_key`, `cover_image_key`, `storage_key`)로 저장하고, base URL은 클라이언트/서버 설정에서 조합합니다.

이 문서는 이 서버 repo에서의 **구현 노트**(스토리지 연동, 업로드 흐름 등)만 둡니다.

## 구현 노트

- 관리자 에셋 업로드 `POST /admin/assets`는 multipart `key`로 전체 object key를 직접 지정할 수 있다. `key`는 선택한 `kind/` prefix로 시작하고 이미지 content type과 확장자가 일치해야 하며, 경로 순회·중복 구분자·URL query 문자는 거부한다. 같은 key가 이미 있으면 S3 조건부 업로드로 덮어쓰지 않고 `409 ASSET_KEY_ALREADY_EXISTS`를 반환한다. `key`를 생략하면 기존 `{kind}/{uuid}.{ext}` 자동 발급을 유지한다.
- 캐릭터 애니메이션: `characters/{code}/animations/{idle|pose-cycle|wave}.webp` (애니메이션 WebP) 규칙으로 S3 에 적재한다. API 는 이 key 를 DB 저장 없이 code 로 파생해 내려주므로(`CharacterAnimations.of`), **새 캐릭터를 카탈로그에 등록하기 전에 애니메이션 3종 적재가 전제 조건**이다 — 빠지면 프론트에서 해당 캐릭터 애니메이션이 404 가 된다. 포맷은 애니메이션 WebP 로 확정(2026-07-15) — APNG 는 RN Android 미재생이라 전환했고, 원본 APNG(.png)는 S3 에 보존돼 있다. 신규 제작 시 APNG 로 만들어도 Pillow 로 일괄 변환해 .webp 로 적재한다.
- 캐릭터 추가 포즈: 규칙 기반 3종과 별도로 `character_poses`에 `character_id`, `code`, `asset_key`, `sort_order`, `is_active`를 저장한다. `GET /api/v1/characters`와 `GET /api/v1/me/characters`의 `poses[]`는 활성 포즈만 정렬 순서대로 반환한다. 관리자는 `/assets`의 characters 탭에서 S3 key를 캐릭터 포즈로 등록·해제할 수 있다.
- 관리자 characters 에셋 삭제는 DB에서 `characters.base_asset_key` 또는 `character_poses.asset_key`로 사용 중이면 거부한다. 미사용 파일은 `archive/admin-deleted/{timestamp}/`에 복구용 사본을 만든 뒤 원래 key를 삭제한다.
- 가구 애니메이션: `items/{theme}/furniture/{slug}-animated-v{N}.webp` 규칙으로 S3 에 적재한다. 관리자 페이지의 `움짤만 보기` 필터도 이 명명 규칙을 기준으로 분류한다. 정적 WebP와 구분해야 하므로 확장자만으로 애니메이션 여부를 판단하지 않는다.

## Asset Foundry 제작·승인 흐름

- 로컬 제작 결과는 `tools/asset-pipeline/assetctl.py`로 검증하고 QA report JSON을 생성합니다. 중간 이미지와 report는 `${TMPDIR:-/tmp}` 아래에 두며 바이너리를 저장소에 커밋하지 않습니다.
- 관리자 `/asset-foundry`는 이미지 생성기를 서버에서 실행하지 않습니다. manifest 상태, 자동 QA, 실제 `room-render-contract.v1.json` 기반 미리보기, 사람 승인, S3·DB·seed·모바일 검증 이력만 관리합니다.
- 상태는 `DRAFT → BUILT → VALIDATED → REVIEW_CANDIDATE → APPROVED → UPLOADED → LINKED_DEV → SEED_SYNCED → CLIENT_VERIFIED` 순서로만 진행합니다. 필수 QA가 모두 PASS가 아니면 `VALIDATED`로 이동할 수 없습니다.
- 같은 S3 key 덮어쓰기를 기본 흐름으로 삼지 않습니다. `-animated-v{N}`처럼 버전 key를 올리고, 기존 아이템 교체 작업은 `targetItemId`와 `expectedOldAssetKey`를 함께 기록해 CAS 전제와 rollback lineage를 보존합니다.
- Asset Foundry의 `UPLOADED` 이후 상태는 작업 이력입니다. 실제 S3 업로드·기존 아이템 연결 교체·seed 변경은 각각의 운영 도구가 수행하며, 상태 변경만으로 외부 상태가 자동 변경되지 않습니다.
- 뽑기 선물상자: `GET /api/v1/gacha`와 `GET /api/v1/gacha/{id}`의 `giftBoxAssetKey`로 테마별 투명 PNG key를 내려준다. 프론트는 다른 이미지와 동일하게 CDN base URL과 조합한다. 테마가 없거나 아직 매핑되지 않은 머신은 기본 선물상자를 사용하며, 불투명 핑크 배경이 남은 `items/698ebc78-8273-4bf7-85d4-a7ea81c7c4d0.png`는 응답에 사용하지 않는다.
- 사용/미사용 토글: 관리자 `/catalog` 화면에서 아이템·캐릭터의 `is_active`를 전환한다(`PUT /admin/catalog/items/{id}/active`, `PUT /admin/catalog/characters/{id}/active`). 상점·캐릭터 목록·뽑기(추첨/보상 미리보기)가 전부 조회 시점에 `is_active`(아이템은 테마 활성까지)를 거르므로 토글 즉시 프론트에 반영된다. 활성화 시 S3 에셋 존재를 검증하며, 캐릭터는 규칙 파생 애니메이션 3종(`characters/{code}/animations/{idle|pose-cycle|wave}.webp`)까지 확인해 없으면 거부한다 — 프론트 404 를 사전에 막기 위함. 카탈로그 적재(import)는 insert-only 이므로 기존 행의 노출 제어는 이 토글이 담당한다.

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
