# 방 / 공동집 도메인 (서버 구현 노트)

도메인 계약(기능·API·데이터)의 **정본은 spec repo**에 있습니다. 이 문서와 어긋나면 spec이 우선입니다.

- 개인 방: [rougether-spec](https://github.com/TripleS-soma/rougether-spec) repo의 `domains/room/`
- 공동 집: 같은 repo `domains/house/`
- 전체 ERD: 같은 repo `erd.md` · API 공통 규약: 같은 repo `api.md`

이 문서는 이 서버 repo에서의 **구현 노트**(Spring 패키지 구조, 트랜잭션 경계, 서버 특이사항)만 둡니다.

## 구현 노트

- `GET /api/v1/houses/cover-images`는 게시 승인된 집 커버 manifest를 `code` 오름차순으로 반환합니다.
- manifest는 `house.cover-images.items` 설정으로 관리하며, S3 `house/`의 초안·중복 파일은 자동 노출하지 않습니다.
- 응답은 프론트 식별·표시용 `code`, `name`과 이미지 로딩용 `coverImageKey`를 제공합니다. 전체 URL이나 S3 운영 메타데이터는 노출하지 않습니다.

### 방 자유배치 (free placement, #162)

- 배치 데이터 정본은 `personal_rooms.layout_format`이 결정합니다 — `SLOT_V1`이면 `room_surface_slots`(11슬롯), `FREE_V1`이면 `room_item_placements`(+ surface 슬롯). 자유배치 첫 저장(`PUT /rooms/me/layout` 성공, 내용물 유무 무관) 시 그 방만 지연 전환되며 역방향 전환은 없습니다. 전환 후에도 기존 positioned 슬롯 row는 구버전 표시 fallback으로 남깁니다.
- 방을 쓰는 두 저장 경로(`updateSlots`·`updateLayout`)는 모두 `PersonalRoomRepository.findWithLockById`(PESSIMISTIC_WRITE)로 같은 방 행을 잠급니다. 락 없이 layout_format을 읽으면 동시 전환을 우회한 positioned 저장이 정본에 반영되지 않는 row를 남기므로, 새 저장 경로를 추가할 때도 이 락을 우회하면 안 됩니다.
- `layout_revision`은 낙관적 잠금 값입니다. layout 저장은 `baseRevision` 불일치 시 409(`ROOM_LAYOUT_REVISION_CONFLICT`)로 거부하고, 슬롯 저장도 성공 시 revision을 1 올려 다른 기기의 stale 저장을 막습니다.
- placements 전체 교체는 bulk delete(`@Modifying(flushAutomatically = true)`) 후 insert 순서라 같은 (room, userItem) 재배치가 unique 충돌 없이 통과합니다. `clearAutomatically`는 쓰지 않습니다 — 락 조회한 PersonalRoom이 detach되어 이후 전환·revision 변경이 유실됩니다.
- 같은 가구(item)는 방에 1개만 배치됩니다 — `user_items`가 V8부터 `UNIQUE(user_id, item_id)`이고 placements도 `UNIQUE(room_user_id, user_item_id)`이기 때문. 다중 배치가 필요해지면 placements unique 완화로 별도 이슈 대응합니다.
- 좌표(0.0~1.0)·scale은 DB 컬럼 정밀도(DECIMAL(6,5)/(4,2))로 반올림해 저장합니다 — 저장 직후 응답과 이후 조회가 일치해야 하기 때문. 겹침·placementType 매칭 검증은 서버가 하지 않습니다(클라이언트 책임, 슬롯 저장과 동일 정책).
- `FREE_V1` 방에 구버전 슬롯 저장이 오면 positioned 슬롯이 포함된 경우에만 409(`ROOM_LAYOUT_FORMAT_CONFLICT`), surface 3종만이면 허용합니다.
