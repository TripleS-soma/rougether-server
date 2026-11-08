# 빼빼로데이 자동 접속 선물

## 동작

한국 시간 2026년 11월 11일에 접속한 일반 사용자 계정에 111코인과 한정 가구 `11:11 소원 관측기`를 한 번씩 지급합니다. 별도 출석 버튼이나 앱 업데이트가 필요하지 않습니다. 가구는 보관함에 들어가며 자동 배치하지 않습니다.

- 이벤트 코드: `PEPERO_DAY_20261111`
- 시작: `2026-11-11T00:00:00+09:00` (`2026-11-10T15:00:00Z`), 포함
- 종료: `2026-11-12T00:00:00+09:00` (`2026-11-11T15:00:00Z`), 제외
- 대상: 유효 JWT를 가진 `/api/v1/` 요청, 소셜 또는 개발용 로그인 성공, refresh 성공. OPTIONS는 제외합니다.
- 당일 신규 가입자와 온보딩 미완료 사용자도 대상입니다. 봇, AI, 탈퇴 사용자는 제외합니다.
- 로그인 상태로 앱을 열거나 복귀해서 API를 호출하면 지급됩니다. 네트워크 요청 없이 화면만 켜져 있는 상태를 접속으로 관측할 수는 없습니다.
- 날짜, 대상 사용자, 코인 수량과 보상 가구를 클라이언트가 지정할 수 없습니다. 사용자별 시간대 설정과 무관하게 이 행사는 한국 시간을 사용합니다.

## 정합성과 실패 처리

`login_gift_events`는 이벤트 설정이며, `login_gift_participations`는 사용자별 접속 자격과 지급 내역입니다. 이벤트 기간과 보상은 운영 중 변경하지 않습니다.

1. 인증된 접속 시각을 캡처하고 사용자 행을 잠근 뒤 참여 기록을 독립 트랜잭션으로 커밋합니다. `(event_id, user_id)`가 유니크합니다.
2. 다른 트랜잭션에서 사용자, 참여 기록, 코인 지갑 순서로 잠급니다. 111코인, `wallet_histories`, 가구와 지급 완료를 함께 커밋합니다.
3. 지갑 원장은 `reason=LOGIN_GIFT`, `source_type=LOGIN_GIFT_PARTICIPATION`, `source_id=참여 기록 ID`로 남깁니다.
4. 지급 완료 캐시는 커밋 성공 후에만 남깁니다. DB의 지급 완료 기록이 정본이므로 다중 인스턴스, 프로세스 재시작, 응답 유실과 재호출에도 중복 지급하지 않습니다.
5. 이미 가구를 보유하면 해당 가구를 지급 내역에 연결하고 코인은 지급합니다. 지급 완료 후 가구를 판매하거나 보관함에서 빠져도 다시 지급하지 않습니다.

접속 자격 저장 실패 시 `503 LOGIN_GIFT_CAPTURE_UNAVAILABLE`로 재시도를 유도합니다. 접속 자격이 저장된 뒤 지급만 실패하면 원래 요청은 계속 처리하며, 다음 접속이나 30초 간격 재처리로 복구합니다. 재처리는 현재 날짜가 아니라 저장된 `eligible_at`을 확인하므로 12일 이후에도 11일 접속분을 지급할 수 있습니다. 한 참여자의 실패가 다른 참여자의 처리를 막지 않습니다.

`eligible_at`은 마이크로초로 절삭해 `TIMESTAMP(6)` 저장 반올림으로 자정 직전 접속이 다음 날로 바뀌지 않게 합니다. MySQL 마이그레이션의 세션 시간대를 UTC로 고정한 뒤 복원하며, H2에서는 해당 설정을 실행 주석으로 건너뜁니다.

행사 밖의 일반 요청은 선물 DB를 조회하지 않도록 캡처 기간을 별도로 제한합니다. 기본값은 위 행사 기간입니다. 다음 행사를 재사용할 때는 아래 캡처 범위와 DB 이벤트 기간을 함께 설정해야 합니다.

- `login-gift.capture-starts-at` / `LOGIN_GIFT_CAPTURE_STARTS_AT`
- `login-gift.capture-ends-at` / `LOGIN_GIFT_CAPTURE_ENDS_AT`
- `login-gift.retry.enabled` (기본 true): false는 자동 복구만 중단합니다. 신규 접속 지급 중단은 `login_gift_events.is_enabled=false`로 설정합니다.
- `login-gift.retry.delay-ms` (기본 30000)

카탈로그 캐시는 최대 30초 후 갱신합니다. 비활성화 후 캐시에 남은 이벤트도 capture와 grant에서 DB 설정을 다시 확인하므로 새 지급이 되지 않습니다.

탈퇴 잔여 데이터 파기 시 참여 기록을 `user_items`보다 먼저 삭제합니다.

## 프론트 연동

자동 지급에는 프론트 변경이 필요하지 않습니다. 아래 API는 안내 팝업을 추가할 때 사용합니다.

### `GET /api/v1/events/login-gifts`

JWT 인증된 사용자 자신의 참여 내역을 반환합니다. 이벤트 종료 뒤에도 조회할 수 있습니다.

```json
{
  "gifts": [{
    "participationId": 123,
    "code": "PEPERO_DAY_20261111",
    "title": "빼빼로데이 접속 선물",
    "eligibleAt": "2026-11-10T15:01:00Z",
    "grantedAt": "2026-11-10T15:01:00Z",
    "acknowledgedAt": null,
    "coinAmount": 111,
    "reward": {
      "itemId": 456,
      "name": "11:11 소원 관측기",
      "assetKey": "items/pepero-day-20261111/furniture/wish-observatory-v1.png",
      "userItemId": 789
    }
  }]
}
```

- `grantedAt=null`: 접속 자격은 기록됐고 지급 대기 중입니다. 수령 완료 팝업을 띄우지 않습니다.
- `grantedAt!=null && acknowledgedAt=null`: 지급 완료 안내를 표시할 수 있습니다.
- `reward.userItemId`는 지급 당시 가구의 식별자입니다. 이후 판매했다면 활성 보관함 항목이 아닐 수도 있습니다.

### `POST /api/v1/events/login-gifts/{participationId}/acknowledgement`

요청 body 없이 지급 완료된 본인 선물의 안내 확인 시각만 기록합니다. 응답은 204이며 재호출은 멱등합니다. 타인이나 없는 참여 ID는 404입니다. 아직 지급 대기 중이면 확인 처리하지 않습니다. 보상을 지급하는 API는 아닙니다.

## 가구와 배포

- `V97__add_login_gifts.sql`이 테이블, 한정 가구와 행사 설정을 함께 생성합니다. 배포 전 최신 main과 마이그레이션 버전 충돌을 재확인합니다.
- 테마는 비활성, 가구는 활성 상태로 등록합니다. 상점 목록과 뽑기에서 숨기면서 보관함과 방 배치는 사용할 수 있습니다. 구매 가격과 뽑기 풀은 설정하지 않습니다.
- PNG: `deploy/assets/pepero-day-20261111/wish-observatory-v1.png`, RGBA 1199×1312, 900976 bytes.
- SHA-256: `e2e7f96276c71ca06333deefcd0c11f1bc0c54d26d045603b6ec86bb3dbeeabe`.
- 에셋 키: `items/pepero-day-20261111/furniture/wish-observatory-v1.png`.
- 현재 프로젝트 에셋 버킷: `rougether-assets-isb-776158585524`.
- 에셋 CDN: `https://d1eazfl0tw7r0v.cloudfront.net/items/pepero-day-20261111/furniture/wish-observatory-v1.png`.
- S3 신규 조건부 업로드와 CDN HEAD 200을 확인했습니다. 기존 MCP의 기본 AWS 연결은 유효하지 않아 현재 SSO 프로필 `rougether-isb`로 업로드했습니다.
- 이미지 생성은 내장 image_gen을 사용했습니다. 승인한 소원 관측기 시안에서 배경만 제거했으며 프롬프트는 같은 에셋 폴더에 보관합니다.

코드와 마이그레이션은 서버에 배포돼야 자동 지급이 작동합니다. 에셋 업로드 완료와 서버 배포 완료는 별개입니다.

## 검증

`LoginGiftIntegrationTest`는 실제 MySQL 8.4에서 시작과 종료 경계(마지막 1나노초 포함), 기존 JWT 요청, 로그인, 당일 신규 가입, refresh, 동시 접속, 재시작에 해당하는 중복 재처리, 코인과 원장과 가구의 롤백, 자정 이후 복구, 접속 기록 저장 실패, 지급 응답 유실과 재호출, 판매 후 재지급 방지, 봇과 AI와 탈퇴 제외, 안내 확인의 소유권을 검증합니다.

`WithdrawalPurgeTriggerTest`에는 지급 가구를 참조하는 참여 기록을 추가해 FK 삭제 순서를 검증합니다. 전체 검증 명령은 `./gradlew test`, `./gradlew compileJava`, `git diff --check`입니다.
