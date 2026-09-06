# 고양이 앱 아이콘 · 미접속 알림 연동

계약 정본은 spec 저장소의 `domains/app-icon/{prd,features,api}.md`이다. 이 문서는 모바일 연결과 서버 운영을 위한 구현 안내이다.

## 모바일 호출 순서

1. 로그인된 사용자가 앱을 실제로 열거나 foreground로 복귀하면 본문 없이 `POST /api/v1/me/app-activity`를 호출한다. 반환 상태로 아이콘을 선택한다.
2. 계속 foreground에서 사용하는 경우 실제 사용자 활동에 맞춰 호출하되 빈번한 호출은 합친다(예: 5분 이내 중복 호출 제한). background 작업·토큰 refresh·FCM 수신만으로 호출하지 않는다.
3. 루틴·투두 완료/취소 성공 후 `GET /api/v1/me/app-icon`을 조회한다. GET은 `lastForegroundAt`을 갱신하지 않는다. 기존 HTTP 일일 활동 분석 필터와 이 시각은 별개다.
4. 응답의 `state`를 로컬 아이콘 리소스에 매핑한다. `message`는 표시용이며 분기 기준으로 사용하지 않는다. OS가 지원하지 않는 경우 기본 아이콘으로 처리한다.
5. `nextEvaluationAt`은 재조회 힌트이다. 앱 미실행 중 자동 교체를 보장하는 예약 시각이 아니다. 아이콘 교체·번들 등록은 모바일에서 별도로 구현해야 한다.

| 상태 | 제작 시안 파일명 | 적용 조건 |
| --- | --- | --- |
| `NORMAL` | 기존 기본 앱 아이콘 | 기본·미접속 48시간 미만 |
| `MISSING_YOU` | `01-missing-you.png` | 48~96시간 미접속 |
| `TEARY` | `02-teary.png` | 96~168시간 미접속 |
| `SOBBING` | `03-sobbing.png` | 168시간 이상 미접속 |
| `DAILY_SUCCESS` | `04-daily-success.png` | 오늘 루틴 또는 투두 완료 |
| `STREAK_CHAMPION` | `05-streak-champion.png` | 유효 루틴 스트릭 7일 이상 |

성공·왕관이 미접속보다 우선한다. 파일명은 제작 시안의 이름이며 앱에 등록된 네이티브 식별자는 모바일에서 정한다. 서버 DB에 이미지 URL을 저장하지 않는다.

## 서버 구성

- `domain.appicon.AppIconPolicy`: API와 배치의 순수 상태 판정. 기존 스트릭의 오늘 기준 유효값과 완료 기록을 읽는다.
- `userapi.appicon.AppIconService`: 인증된 본인의 상태 조회·foreground 기록. 사용자 행을 잠근 뒤 서버 시각을 기록한다.
- `batch.appicon.AppIconReminderTrigger`: KST 30분 주기와 서버 시작 시 확인. 09:00 이상 21:00 미만에만 적재·발송한다.
- `batch.appicon.AppIconReminderService`: 사용자별 알림 내역·최고 단계를 한 트랜잭션으로 적재하고, 별도 트랜잭션에서 발송 조건을 재검증한다. 사용자 ID/알림 ID 커서로 순회하므로 처리 중 대상이 줄어도 누락하지 않는다.
- 기존 `ReminderPushWriter`·`NotificationPushPolicy`·`FcmSender`를 재사용한다. `APP_INACTIVITY_REMINDER`는 `REMINDER` 그룹이고, `ALL` 또는 `REMINDER` off면 푸시는 차단한다. 알림함 내역은 유지한다.
- 신규 Flyway `V65__add_app_icon_activity.sql`: 사용자당 활동 1행과 마지막 알림 단계·ID. 기존 접속 시각으로 backfill하지 않는다. 회원탈퇴 즉시 삭제하고 purge도 잔존 데이터를 정리한다.

## 발송·중복 처리

48·96·168시간에 해당하는 알림을 단계별 한 번 적재한다. 배치가 늦게 실행되면 현재 가장 높은 단계만 만든다. 실제 foreground 기록 시 최고 단계와 최신 알림 ID를 초기화해 새 회차를 시작한다.

사용자 행 잠금은 적재·발송·foreground·탈퇴에서 같은 순서를 사용한다. 발송 전 복귀나 성취 상태, 이전 회차·단계 여부를 다시 확인한다. 오래된 대기 알림은 `BLOCKED`, 토큰 없음·발송 실패는 기존 정책대로 `FAILED`이며 자동 재시도하지 않는다. DB 장애로 커밋되지 않은 작업은 다음 실행에서 다시 확인한다.

FCM 발송은 기존 경로와 같은 best-effort이다. 사용자 잠금은 동시 실행의 중복 호출을 막지만, FCM 수락 후 DB 상태 커밋 전 프로세스 종료까지 포함한 exactly-once는 보장하지 않는다. 네트워크 발송 동안 사용자 행 잠금을 유지하므로 같은 사용자의 foreground 요청은 발송 완료까지 대기할 수 있다.

FCM `data`에는 `type=APP_INACTIVITY_REMINDER`, `screen=myRoom`, `notificationId`(문자열)를 전달한다. 모바일은 알림 type을 확인해 탭 시 내 방을 연다. 푸시 수신 자체는 실제 foreground 활동으로 기록하지 않는다.

## 배포·검증

- 서버 migration 및 user-api·batch를 먼저 배포하고 모바일에서 foreground API와 상태별 아이콘을 연결한다. 신규 API를 호출한 기록이 생겨야 미접속 알림 대상이 된다.
- batch의 `APP_ICON_REMINDER_ENABLED` 기본값은 `true`이다. `false`면 신규 트리거를 비활성화한다. 다른 리마인더에는 영향이 없다.
- Firebase 자격증명·FCM 토큰은 기존 구성과 API를 사용한다. 실서비스 발송 확인은 테스트 계정과 기기에서 진행한다.
- 검증: `./gradlew compileJava`, `./gradlew test`, `git diff --check`.
- 신규 테스트는 48·96·168시간 경계, KST 자정·유효 스트릭, 과거 루틴·무기한 투두, 완료 취소, 인증 주체, 동시 첫 활동, 반복 배치·복귀·설정 off·동시 발송·롤백·발송 실패를 다룬다. 기존 MySQL 탈퇴·purge 테스트에도 활동 데이터 정리를 추가한다.
