# API Priority

이 문서는 MVP 기능명세 이후 백엔드 구현 순서를 정하기 위한 API 우선순위 초안입니다.

API는 화면 목록이 아니라 사용자가 실제로 지나가는 핵심 흐름 기준으로 나눕니다.

```text
가입/로그인
  -> 프로필 설정
  -> 루틴/투두 생성
  -> 오늘 할 일 조회
  -> 루틴/투두 수행
  -> 스트릭/보상 반영
  -> 개인 방 상태 확인
  -> 공동집에서 구성원 방 확인
```

## Priority Rule

### P0

앱의 MVP 루프가 끊기지 않게 만드는 API입니다.

- 사용자가 앱에 들어올 수 있어야 한다.
- 오늘 할 루틴과 투두를 볼 수 있어야 한다.
- 수행 기록이 남고, 보상과 스트릭이 반영되어야 한다.
- 개인 방 상태를 조회하고 고정 슬롯에 아이템을 배치할 수 있어야 한다.
- 공동집을 만들거나 참여하고, 구성원의 방 상태를 볼 수 있어야 한다.

### P1

MVP의 재미와 반복 사용을 강화하는 API입니다.

- 상점, 아이템 구매, 캐릭터 꾸미기
- 공동집 목표, 방명록, 구성원 상호작용
- 기본 통계와 주간 요약

### P2

초기 구현 이후 붙여도 되는 확장 API입니다.

- AI 사진 판단, AI 루틴 추천, AI 주간 회고
- 뽑기 고도화, 특수 재화 확장
- 광고, 구독, 알림, 운영성 기능

## P0 APIs

P0는 첫 구현 단위입니다. 이 API들이 잡힌 뒤 migration과 entity를 작성합니다.

### 1. Auth / User

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/auth/social-login` | 소셜 로그인 후 서비스 토큰 발급 |
| `POST` | `/api/v1/auth/refresh` | access token 재발급 |
| `DELETE` | `/api/v1/auth/logout` | 현재 세션 로그아웃 |
| `GET` | `/api/v1/users/me` | 내 계정과 프로필 조회 |
| `PATCH` | `/api/v1/users/me/profile` | 닉네임, 대표 캐릭터, 목표 등 프로필 설정 |

우선순위 이유:

- 모든 API의 기준 사용자를 확정한다.
- `users`, `auth_sessions`, `user_profiles`의 최소 필드를 먼저 확정할 수 있다.

### 2. Categories

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/categories` | 내 루틴/투두 카테고리 목록 조회 |
| `POST` | `/api/v1/categories` | 커스텀 카테고리 생성 |
| `PATCH` | `/api/v1/categories/{categoryId}` | 카테고리 이름, 색상, 정렬 수정 |
| `DELETE` | `/api/v1/categories/{categoryId}` | 카테고리 비활성화 또는 삭제 |

우선순위 이유:

- 루틴 카테고리는 운영자가 정하는 값이 아니라 사용자가 직접 만드는 값이다.
- 루틴과 투두가 같은 카테고리 체계를 공유할 수 있어야 한다.

### 3. Routines

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/routines` | 내 루틴 목록 조회 |
| `POST` | `/api/v1/routines` | 루틴 생성 |
| `GET` | `/api/v1/routines/{routineId}` | 루틴 상세 조회 |
| `PATCH` | `/api/v1/routines/{routineId}` | 루틴명, 카테고리, 인증 방식 수정 |
| `PATCH` | `/api/v1/routines/{routineId}/schedule` | 요일, 시간, 횟수, 기간 수정 |
| `DELETE` | `/api/v1/routines/{routineId}` | 루틴 종료 또는 삭제 |

우선순위 이유:

- 루틴 앱의 핵심 생성 흐름이다.
- 반복 요일, 시간, 횟수, 기간이 확정되어야 오늘 루틴 조회와 스트릭 계산이 가능하다.

### 4. Todos

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/todos` | 내 투두 목록 조회 |
| `POST` | `/api/v1/todos` | 단발성 투두 생성 |
| `PATCH` | `/api/v1/todos/{todoId}` | 투두 내용, 마감일, 카테고리 수정 |
| `DELETE` | `/api/v1/todos/{todoId}` | 투두 삭제 |

우선순위 이유:

- 루틴은 반복 행동이고 투두는 단발 행동이다.
- 오늘 화면에서 루틴과 투두를 함께 보여주려면 별도 API와 테이블이 필요하다.

### 5. Today

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/today` | 오늘 루틴, 투두, 진행률, 보상 요약 조회 |
| `POST` | `/api/v1/daily-check-ins` | 앱 접속 기반 일일 체크인 기록 |

우선순위 이유:

- 앱 홈 화면의 기준 API다.
- 수행 대상 루틴이 없는 날에도 접속 체크인을 통해 일일 스트릭을 관리할 수 있다.

### 6. Completion / Streak

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/routine-completions` | 루틴 완료 기록 |
| `DELETE` | `/api/v1/routine-completions/{completionId}` | 당일 루틴 완료 취소 |
| `POST` | `/api/v1/todo-completions` | 투두 완료 기록 |
| `DELETE` | `/api/v1/todo-completions/{completionId}` | 투두 완료 취소 |
| `GET` | `/api/v1/streaks/me` | 개인 통합 스트릭과 루틴별 스트릭 조회 |

우선순위 이유:

- 완료 처리는 보상, 스트릭, 방 성장, 공동집 기여도와 연결되는 핵심 쓰기 흐름이다.
- 완료 API 응답에서 갱신된 보상, 진행률, 스트릭 값을 함께 내려주면 프론트가 즉시 반응할 수 있다.

### 7. Wallet / Reward

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/wallets/me` | 내 기본 재화와 특수 재화 잔액 조회 |
| `GET` | `/api/v1/wallets/me/ledger` | 재화 변동 내역 조회 |

우선순위 이유:

- 루틴 완료 보상이 실제로 반영되었는지 확인할 수 있어야 한다.
- 아이템 구매와 뽑기 확장을 위해 잔액과 변동 내역 모델이 필요하다.

### 8. Room / Asset Placement

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/rooms/me` | 내 방 상태, 배치 슬롯, 대표 캐릭터 조회 |
| `GET` | `/api/v1/items/catalog` | 배치 가능한 아이템 카탈로그 조회 |
| `GET` | `/api/v1/items/me` | 내가 보유한 아이템 조회 |
| `PUT` | `/api/v1/rooms/me/slots/{slotKey}` | 고정 슬롯에 아이템 배치 또는 교체 |
| `DELETE` | `/api/v1/rooms/me/slots/{slotKey}` | 슬롯 비우기 |

우선순위 이유:

- MVP는 자유배치보다 고정 슬롯 기반으로 가는 편이 구현과 QA가 안정적이다.
- 방 상태는 앱의 핵심 보상 화면이므로 루틴 완료 이후 바로 조회 가능해야 한다.

### 9. Household

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/households` | 내가 속한 공동집 목록 조회 |
| `POST` | `/api/v1/households` | 공동집 생성 |
| `POST` | `/api/v1/households/join` | 초대코드 또는 공개 공동집 참여 |
| `GET` | `/api/v1/households/{householdId}` | 공동집 상세 조회 |
| `GET` | `/api/v1/households/{householdId}/members` | 공동집 구성원 조회 |
| `GET` | `/api/v1/households/{householdId}/rooms` | 구성원 방 미리보기 목록 조회 |

우선순위 이유:

- 공동집은 단순 그룹 정보가 아니라 구성원 방 상태를 함께 보여주는 화면이다.
- 다른 사람의 방 에셋까지 조회해야 하므로 응답 구조를 초기에 잡아두는 것이 중요하다.

## P1 APIs

P1은 MVP 루프가 돈 뒤 붙입니다.

| Area | APIs | Reason |
| --- | --- | --- |
| Item Shop | `POST /api/v1/items/purchases` | 재화 사용처를 만든다. |
| Character | `GET /api/v1/characters/me`, `PUT /api/v1/characters/me/{characterId}/equipment/{slotKey}` | 캐릭터 소유와 꾸미기 아이템 장착을 분리한다. |
| Household Goal | `GET/POST/PATCH/DELETE /api/v1/households/{householdId}/goals` | 공동집의 장기 목표를 여러 개 관리한다. |
| Guestbook | `GET/POST /api/v1/rooms/{roomId}/guestbook-messages` | 방명록은 공동집 전체가 아니라 각 방에 종속된다. |
| Stats | `GET /api/v1/stats/daily`, `GET /api/v1/stats/weekly` | 루틴 수행 결과를 요약한다. |

## P2 APIs

P2는 서비스 완성도를 높이는 확장입니다.

| Area | APIs | Reason |
| --- | --- | --- |
| Gacha | `GET /api/v1/gacha/banners`, `POST /api/v1/gacha/pulls` | 특수 재화와 확률형 보상 정책이 필요하다. |
| AI Photo Check | `POST /api/v1/ai/photo-checks` | 사진 인증 보조 판단은 핵심 CRUD 이후 붙인다. |
| AI Recommendation | `POST /api/v1/ai/routine-recommendations` | 추천 품질과 비용 정책을 함께 봐야 한다. |
| AI Review | `POST /api/v1/ai/weekly-reviews` | 통계 데이터가 쌓인 뒤 가치가 생긴다. |
| Notification | `GET/PATCH /api/v1/notification-settings` | 루틴 리마인더와 집 미션 알림을 제어한다. |
| Monetization | `GET /api/v1/subscriptions/me`, `POST /api/v1/ad-rewards` | 광고와 구독 정책 확정 후 구현한다. |

## First Implementation Order

1. Auth / User
2. Categories
3. Routines / Todos
4. Today
5. Completion / Streak / Reward
6. Room / Item placement
7. Household minimal view

이 순서로 가면 첫 번째 MVP 데모에서 사용자는 아래 흐름을 끝까지 볼 수 있습니다.

```text
로그인
  -> 루틴 생성
  -> 오늘 루틴 확인
  -> 루틴 완료
  -> 스트릭과 재화 증가
  -> 방 아이템 배치
  -> 공동집에서 내 방과 구성원 방 확인
```
