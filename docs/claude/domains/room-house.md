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
- 집 탐색 참여는 `house_join_requests`에 PENDING 신청만 만들고, OWNER 수락 시점에만 `house_members` ACTIVE 등록과 `current_member_count` 증가를 처리합니다. 초대코드 참여는 즉시가입을 유지하며 대기 신청이 있으면 ACCEPTED로 함께 종결합니다.
- 초대코드는 두 종류입니다 (V40). 집 공용 코드(`house.invite_code`, 소유자 재발급)는 즉시가입, 구성원 개인 코드(`house_members.invite_code`, 일반 구성원이 `POST /houses/{houseId}/invite-code`로 재발급)는 탐색 신청과 같은 PENDING 입주 신청을 만들어 방장 수락으로 확정합니다. `join-by-code`·`by-code` 미리보기는 집 코드 → 구성원 코드 순으로 조회하고, 두 네임스페이스는 발급 시점(`InviteCodeGenerator`)에 겹치지 않게 보장합니다. 개인 코드의 만료·유효성은 초대자 구성원 행 기준이라 초대자가 탈퇴·강퇴되면 코드도 즉시 무효입니다. 초대자가 참여 시점에 OWNER면(양도 등) 개인 코드도 즉시가입으로 처리합니다.
- 신청 생성·수락·거절·초대코드 가입은 모두 먼저 `house` 행을 잠급니다. 수락은 같은 잠금 안에서 정원을 다시 확인하므로 동시 수락으로 정원을 넘기지 않습니다.
- `GET /api/v1/houses`(탐색)는 `excludeJoined=true` 파라미터로 본인이 지금 가입(ACTIVE)해 있는 집을 제외할 수 있습니다. 탈퇴(LEFT)·강퇴(KICKED) 이력만 있는 집은 계속 목록에 포함되며, 기본값(false)은 기존과 동일하게 가입한 집도 포함합니다. goalCode 필터와 함께 쓸 수 있습니다.
- 루틴(`routines.house_mission_id`)·카테고리(`categories.house_id`)의 집/단체미션 연동 값 검증(`HouseLinkValidator`)은 연동 대상 집의 ACTIVE 구성원만 통과시킵니다. 미션 삭제·집 탈퇴/강퇴 시 이 도메인의 트랜잭션(`HouseMissionService.delete`/`HouseMemberCommandService.leave·kick`)이 연동을 일괄 해제하며, 루틴·카테고리 데이터 자체는 삭제하지 않습니다. 상세는 [routine-todo.md](routine-todo.md) 구현 노트 참고.
- `GET /api/v1/houses/{houseId}/preview`는 비구성원에게도 `missions` 요약과 진행도를 읽기 전용으로 제공합니다. 별도 미션 목록·상세·기여·보상 API의 ACTIVE 구성원 guard는 유지하며, 미리보기 응답에는 개인 기여값이나 실행 권한을 포함하지 않습니다.

### 방 자유배치 (free placement, #162)

- 배치 데이터 정본은 `personal_rooms.layout_format`이 결정합니다 — `SLOT_V1`이면 `room_surface_slots`(11슬롯), `FREE_V1`이면 `room_item_placements`(+ surface 슬롯). 자유배치 첫 저장(`PUT /rooms/me/layout` 성공, 내용물 유무 무관) 시 그 방만 지연 전환되며 역방향 전환은 없습니다. 전환 후에도 기존 positioned 슬롯 row는 구버전 표시 fallback으로 남깁니다.
- 방을 쓰는 두 저장 경로(`updateSlots`·`updateLayout`)는 모두 `PersonalRoomRepository.findWithLockById`(PESSIMISTIC_WRITE)로 같은 방 행을 잠급니다. 락 없이 layout_format을 읽으면 동시 전환을 우회한 positioned 저장이 정본에 반영되지 않는 row를 남기므로, 새 저장 경로를 추가할 때도 이 락을 우회하면 안 됩니다.
- `layout_revision`은 낙관적 잠금 값입니다. layout 저장은 `baseRevision` 불일치 시 409(`ROOM_LAYOUT_REVISION_CONFLICT`)로 거부하고, 슬롯 저장도 성공 시 revision을 1 올려 다른 기기의 stale 저장을 막습니다.
- placements 전체 교체는 bulk delete(`@Modifying(flushAutomatically = true)`) 후 insert 순서라 같은 (room, userItem) 재배치가 unique 충돌 없이 통과합니다. `clearAutomatically`는 쓰지 않습니다 — 락 조회한 PersonalRoom이 detach되어 이후 전환·revision 변경이 유실됩니다.
- 같은 가구(item)는 방에 1개만 배치됩니다 — `user_items`가 V8부터 `UNIQUE(user_id, item_id)`이고 placements도 `UNIQUE(room_user_id, user_item_id)`이기 때문. 다중 배치가 필요해지면 placements unique 완화로 별도 이슈 대응합니다.
- 좌표(0.0~1.0)·scale은 DB 컬럼 정밀도(DECIMAL(6,5)/(4,2))로 반올림해 저장합니다 — 저장 직후 응답과 이후 조회가 일치해야 하기 때문. 겹침·placementType 매칭 검증은 서버가 하지 않습니다(클라이언트 책임, 슬롯 저장과 동일 정책).
- `FREE_V1` 방에 구버전 슬롯 저장이 오면 positioned 슬롯이 포함된 경우에만 409(`ROOM_LAYOUT_FORMAT_CONFLICT`), surface 3종만이면 허용합니다.
