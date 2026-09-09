# 방 / 공동집 도메인 (서버 구현 노트)

도메인 계약(기능·API·데이터)의 **정본은 spec repo**에 있습니다. 이 문서와 어긋나면 spec이 우선입니다.

- 개인 방: [rougether-spec](https://github.com/TripleS-soma/rougether-spec) repo의 `domains/room/`
- 공동 집: 같은 repo `domains/house/`
- 전체 ERD: 같은 repo `erd.md` · API 공통 규약: 같은 repo `api.md`

이 문서는 이 서버 repo에서의 **구현 노트**(Spring 패키지 구조, 트랜잭션 경계, 서버 특이사항)만 둡니다.

## 구현 노트

- 최초 소셜 로그인·dev-login 신규 가입 트랜잭션에서 지갑과 기본 공동집을 함께 생성합니다. 기본 집 생성 실패 시 가입 전체가 롤백됩니다.
- 기본 집은 `is_public=false`, 이름 `나의 집`, 정원 4명, 게시 승인 커버 manifest의 첫 항목으로 시작합니다. 탐색·비구성원 미리보기·탐색형 입주 신청에서는 제외하지만 초대코드 조회·참여와 ACTIVE 구성원 기능은 유지합니다. 집 목표는 비워 두고 첫 온보딩 목표 저장에서 최대 3개를 한 번만 연결합니다.
- 공개 여부는 집 설정(`PUT /api/v1/houses/{houseId}`)의 `isPublic`으로 소유자가 양방향 전환합니다(#350). 기본 집 이름은 닉네임 저장(`PUT /api/v1/me`) 시 아직 `나의 집`이면 `{닉네임}의 집`으로 개명되며(`HouseCommandService.renameDefaultNamedHouses`), 직접 지은 이름은 건드리지 않고 이후 닉네임 변경에도 따라가지 않습니다. V62는 혼자 있는 집(봇 제외 실사용자 1명 이하)을 일괄 비공개+개명 백필했습니다.
- `GET /api/v1/houses/cover-images`는 게시 승인된 집 커버 manifest를 `code` 오름차순으로 반환합니다.
- manifest는 `house.cover-images.items` 설정으로 관리하며, S3 `house/`의 초안·중복 파일은 자동 노출하지 않습니다.
- 응답은 프론트 식별·표시용 `code`, `name`과 이미지 로딩용 `coverImageKey`를 제공합니다. 전체 URL이나 S3 운영 메타데이터는 노출하지 않습니다.
- 집 탐색 참여는 `house_join_requests`에 PENDING 신청만 만들고, OWNER 수락 시점에만 `house_members` ACTIVE 등록과 `current_member_count` 증가를 처리합니다. 초대코드 참여는 즉시가입을 유지하며 대기 신청이 있으면 ACCEPTED로 함께 종결합니다.
- 초대코드는 두 종류입니다 (V40). 집 공용 코드(`house.invite_code`, 소유자 재발급)는 즉시가입, 구성원 개인 코드(`house_members.invite_code`, 일반 구성원이 `POST /houses/{houseId}/invite-code`로 재발급)는 탐색 신청과 같은 PENDING 입주 신청을 만들어 방장 수락으로 확정합니다. `join-by-code`·`by-code` 미리보기는 집 코드 → 구성원 코드 순으로 조회하고, 두 네임스페이스는 발급 시점(`InviteCodeGenerator`)에 겹치지 않게 보장합니다. 개인 코드의 만료·유효성은 초대자 구성원 행 기준이라 초대자가 탈퇴·강퇴되면 코드도 즉시 무효입니다. 초대자가 참여 시점에 OWNER면(양도 등) 개인 코드도 즉시가입으로 처리합니다.
- 집 초대코드는 공개 랜딩 `GET /h/{code}`(비인증 HTML)로도 공유할 수 있습니다. 미설치 사용자를 스토어로 보내며 설치 후 코드 자동 입력(deferred deep link)까지 잇는 구조는 [invite.md](invite.md)를 참고합니다.
- `join-by-code`·`by-code` 미리보기의 입력 코드는 서버가 trim+대문자로 정규화합니다(친구 초대 redeem과 동일 규칙). 링크·수기 입력의 소문자·공백 오입력을 흡수하고, MySQL ci collation에서 집 공용 코드(조회 매칭 성공)와 구성원 개인 코드(자바 `equals` 재검증 실패)의 성패가 갈리던 환경 의존 비일관도 없앱니다.
- 입주 신청(PENDING)이 생성·재오픈되면 방장에게 `HOUSE_JOIN_REQUEST_CREATED` 알림이 갑니다(refId=신청 id, 승인·거절 알림과 대칭). 수락 권한자인 방장이 신청 도착을 몰라 입주 확정이 늦어지는 문제(친구추가 반응 지연 제보) 대응입니다. 같은 (방장, 신청자 닉네임, 집) 조합은 1시간 억제 창 안에서 중복 발송하지 않습니다 — 철회→재신청(신청 행이 새로 생겨 refId 가 바뀜)·거절→재신청(reopen)을 반복해 방장에게 push 를 무제한 밀어넣는 증폭 방어이며, 신청 반복 자체의 제한은 spec open question 입니다. 신청 생성 경로에는 `join()`과 동일한 탈퇴 계정 가드(INVALID_TOKEN)가 걸려, 잔여 토큰의 재신청이 탈퇴 정리로 REJECTED 된 신청을 되살리지 못합니다. 신청자 닉네임이 없으면(온보딩 전·익명화) 본문에 "이웃"으로 표기합니다.
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

### 개인 방 레벨 (2026-09-09)

- `personal_rooms.growth_points`에 실제 루틴·투두 코인 완료 보상과 같은 양을 누적하고, 레벨 L의 최소 누적 포인트 `L × (L + 19)`를 기준으로 레벨을 계산합니다. 현재 레벨 L에서 다음 레벨로 가는 구간 필요량은 `20 + 2 × L`입니다(20 → 22 → 24 → …). 현재 두 종류 모두 건당 10, 합산 하루 최대 50포인트입니다. 소비한 코인과는 독립적입니다.
- `RoomGrowthService`는 호출자의 트랜잭션에 참여합니다(`MANDATORY`). 완료/취소는 일반 조회보다 먼저 user → 코인 지갑 → 방 순서로 잠가 중복 처리와 오래된 스냅샷 사용을 막습니다. 캐릭터를 지급하는 온보딩·뽑기·관리자 경로도 같은 user 잠금을 사용합니다.
- `routine_logs`·`todos`에 `growth_reward_amount`를 저장해 취소 시 실제 지급량만 회수합니다. 보상 0·FAILED 소급 완료는 포인트를 주지 않습니다. 도입 전 완료는 실제 COIN 보상 중 미적립분을 일괄 반영하고, 소급한 완료를 취소하면 기록한 포인트도 회수합니다. 기존 레벨은 배포 전 migration에서 새 곡선의 최소 누적 포인트로 보존합니다.
- 첫 포인트 지급·첫 조회·첫 배치 저장이 겹칠 수 있어 방 생성을 PK upsert로 통일했습니다. 성장과 배치 저장은 같은 방 행 잠금을 사용하고, 성장만 바뀌면 `layoutRevision`은 유지합니다.
- `RoomResponse`에 `growthPoints`(누적)·`pointsToNextLevel`(다음 레벨까지 남은 포인트)을 추가합니다. 예: 누적 30 → 레벨 1, 남은 포인트 12. 누적 50 → 레벨 2, 남은 포인트 16. 문턱을 넘은 포인트는 다음 구간에 유지합니다. 내 방과 인가된 방문 응답에 포함하며 공개 렌더 부분집합은 기존 `growthLevel`만 유지합니다. 완료/취소 후 방 조회로 확인합니다.
- 루틴/투두 삭제는 기존 완료 보상을 보존하므로 포인트도 유지합니다. 완료 취소로 포인트가 레벨 경계 아래로 내려가면 레벨도 낮아집니다. 모바일 연출·레벨별 아이템 보상은 이번 서버 변경 범위 밖입니다.

| 도달 레벨 | 최소 누적 포인트 | 매일 최대 적립 시 최소 기간 |
| --- | --- | --- |
| 1 | 20 | 1일(완료 2회) |
| 5 | 120 | 3일 |
| 10 | 290 | 6일 |
| 20 | 780 | 16일 |

### 모루 레벨 5 달성 보상

- 사용자 확정 범위: 모루(`moru`)를 개인 방 레벨 5 달성 시 지급합니다. 누움은 같은 캐릭터의 추가 포즈입니다. 현재 곡선에서 레벨 5는 누적 120포인트입니다.
- 구현 정책: 최초 5 이상 달성 시 기존 완료 트랜잭션 안에서 지급하며 대표 캐릭터는 바꾸지 않습니다. 완료 취소로 레벨이 하락해도 보유를 유지합니다. `highest_growth_level`에 최고 달성을 보존하고, 재달성·동시 요청·재시도에도 같은 캐릭터는 한 번만 지급합니다. 삭제된 보유 이력도 재지급하지 않습니다.
- 기존 5 이상 사용자와 카탈로그 준비 전에 달성한 사용자는 내 방 또는 보유 캐릭터 목록 조회 시 지급을 보정합니다. 타인 방 조회는 보상 데이터를 변경하지 않습니다. 미등록/비활성 모루는 지급을 보류하고 루틴·투두 완료는 정상 처리합니다.
- 모루는 온보딩 목록·첫 무료 선택·뽑기 미리보기/추첨·봇 기본 지급에서 제외합니다. 먼저 모루를 받은 사용자도 기존 기본 캐릭터 무료 선택권은 유지합니다. 지급받은 모루를 대표로 선택하는 것은 허용합니다. 관리자 QA 직접 지급은 기존 명시적 운영 기능으로 유지합니다.
- 에셋이 준비되기 전 카탈로그를 등록하거나 활성화하지 않습니다. base와 규칙 파생 WebP 3종을 업로드·검증한 뒤 `moru` 카탈로그를 등록합니다. `celebrate`는 `pose-cycle`, 누움은 추가 pose `lying`으로 연결합니다. 로컬 시안과 서버 지급 코드 완성은 S3 적재·DB 활성화·배포 완료와 구분합니다.

### 기존 완료 이력 소급

- 활성 일반 회원의 `COMPLETED` 루틴 로그·투두에서 실제 `COIN` 보상액과 `growth_reward_amount`의 차액만 추가합니다. 지급액이 보존된 soft delete 루틴·투두도 포함하며, 봇·탈퇴 회원·보상 0·다른 통화·FAILED/PENDING은 제외합니다. 남아 있지 않은 완료 이력을 추측해 복원하지 않습니다.
- 코인 잔액·원장·배치·기존 성장 포인트를 유지합니다. 사용자별 user → coin wallet → 완료 이력 → 방 잠금 아래 누락 포인트, 레벨·최고 레벨, 완료별 지급 표식을 한 트랜잭션으로 갱신합니다. 중간 실패 시 해당 사용자만 롤백되고, 재실행 시 이미 반영한 차액은 0이 됩니다.
- `PersonalRoomGrowthBackfill`은 새 user-api의 트래픽 전환이 완료된 뒤 별도로 실행합니다. Flyway/앱 시작 시에는 자동 실행하지 않습니다. 구버전 서버는 취소 시 성장 포인트를 회수하지 못하므로 전환 전에 과거 이력을 적립하면 안 됩니다. 운영 절차는 [소급 반영 절차](../../work/personal-room-growth-backfill.md)를 따릅니다.
- 모루가 활성화되어 있으면 소급 처리와 같은 사용자 트랜잭션에서 한 번 지급합니다. 미등록/비활성이라면 최고 달성 기록을 남기며, 준비 후 내 방·보유 캐릭터 조회에서 보정합니다.
- 모루 앱용 런타임 번들은 로컬 `output/imagegen/moru-runtime-2026-09-09/`에 있습니다. `runtime-manifest.json`에 400×418 RGBA, anchor `(200,396)`, 파일별 해시와 검증 상태를 기록했습니다. S3/카탈로그/모바일 배포 여부는 별도로 검증합니다.
