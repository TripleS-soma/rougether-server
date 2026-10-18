# 공개 SNS 피드 구현·운영

공유 계약은 [spec 피드 API](https://github.com/TripleS-soma/rougether-spec/blob/codex/sns-feed/domains/feed/api.md)와 기능 명세에 둡니다. 이 문서는 백엔드 구현 및 배포 의존성을 설명합니다.

## 자유게시판·인증게시판

- `boardType=FREE`는 자유게시판, `VERIFICATION`은 사진 인증게시판입니다. 새 인증글은 본인 루틴의 완료 기록(`routineCompletion`)을 연결해야 합니다(아래 「인증글 루틴 완료 연결」). 투두 완료 연결은 없습니다.
- 등록 시 종류 생략/null은 기존 앱 호환을 위해 `VERIFICATION`입니다. 자유게시판은 사진 0~10장, 인증게시판은 1~10장입니다. 자유게시판의 사진 목록 생략/null/빈 배열은 동일하며, 사진이 없으면 공백을 제거한 본문이 필수입니다.
- `GET /api/v1/feed/posts?boardType=FREE` 또는 `VERIFICATION`으로 조회합니다. 종류 생략은 기존 통합 피드이며 `authorId`, `cursor`, `size`를 함께 사용할 수 있습니다. 목록·상세·등록·수정 응답에 `boardType`을 반환합니다.
- 사진은 게시 후 변경하지 않습니다. 게시판 종류는 V84부터 `PATCH`로 바꿀 수 있습니다(아래 절). 사진 없는 자유글은 본문 수정으로도 빈 글이 될 수 없습니다. 제목 필드는 추가하지 않습니다.
- 재시도 UUID의 유일성은 게시판을 통틀어 작성자별입니다. 최초 게시판과 다르면 409이며 기존 본문/사진 hash 형식은 유지합니다. 구버전 인증글의 재시도와 수정 후 재시도도 유지됩니다.
- 병합 순서는 서버 #427(refresh token family, V82) → #428(게시판, V83)입니다. #427 적용 전 V83을 먼저 배포하면 기본 out-of-order 금지 설정에서 후속 V82 적용이 막힐 수 있습니다. 병합 직전 최신 main의 버전 중복을 다시 확인합니다.
- V83는 기존 글을 `VERIFICATION`으로 분류하고 게시판별/게시판·작성자별 커서 조회 인덱스를 추가합니다. 기존 통합 목록 인덱스도 유지합니다.
- V83 적용 → API를 포함해 `FeedPost`를 읽는 앱 갱신 → 새 게시판 UI 활성화 순서로 배포합니다. 구버전 서버는 `board_type` 필드를 무시하며 글 수정 시 사진 없는 본문을 비울 수 있으므로 혼합 버전 상태에서 자유글 작성을 열지 않습니다. 자유글이 생긴 뒤에는 본문 검증을 포함한 호환 버전으로 롤백해야 합니다.
- 댓글·좋아요·알림·신고·차단은 게시판 공통으로 사용합니다. 종류를 생략한 통합 목록에도 자유글이 포함되므로 앱이 빈 `images` 배열을 표시할 수 있는지 확인한 뒤 자유글 작성을 활성화합니다. 앱 화면 연결과 배포는 별도 작업입니다.

## 인증글 루틴 완료 연결 (V84)

- `feed_posts`에 `routine_id`, `routine_date`, `routine_title`(모두 nullable)을 추가했습니다. 기존 인증글은 연결 없이 유효하며 백필하지 않습니다. 루틴은 버전 분기·soft delete·탈퇴 정리로 행이 바뀌거나 사라지므로 FK를 두지 않습니다.
- 검증(`FeedRoutineLinks`): 요청 루틴이 본인 소유(삭제·옛 버전 포함)이고, 그 계보(`coalesce(origin_routine_id, id)`)에 해당 날짜 `COMPLETED` 로그가 있어야 합니다. `SKIPPED`·`FAILED`는 인정하지 않습니다. 날짜는 요청 시점 KST 오늘~6일 전(7일)만 허용하고 미래는 거절합니다. 실패 사유는 루틴 존재 여부를 드러내지 않게 모두 `FEED_ROUTINE_COMPLETION_INVALID`(400)입니다.
- 제목은 연결 시점 스냅샷입니다. 계보에 살아 있는 버전이 있으면 그 제목, 없으면 요청한 버전의 제목입니다. 연결 후 이름 변경·삭제·완료 취소는 배지에 반영하지 않습니다. 글 삭제·작성자 탈퇴 정리 시 세 컬럼을 비웁니다.
- 등록: 인증게시판은 `routineCompletion` 필수(`FEED_ROUTINE_COMPLETION_REQUIRED`), 자유게시판은 금지(`FEED_INPUT_INVALID`). 필수 검사는 재시도 판정 뒤에 하므로 V84 이전에 등록된 인증글의 재시도는 원래 결과를 받습니다. 연결이 있으면 요청 hash에 `\u0000routine:{routineId}:{date}`를 덧붙여 같은 UUID로 다른 루틴을 보내면 409입니다. 연결 없는 요청의 hash 형식은 그대로입니다.
- 수정: `boardType`·`routineCompletion`·`content` 모두 선택이며 생략은 유지입니다. 셋 다 생략하면 `FEED_INPUT_INVALID`. 인증게시판으로 옮기려면 사진이 있어야 하고(`FEED_INPUT_INVALID`) 연결이 필요합니다(`FEED_ROUTINE_COMPLETION_REQUIRED`). 자유게시판으로 옮기면 연결을 해제합니다. 인증글로 남으면서 `routineCompletion`을 보내면 교체합니다. 게시판을 바꾼 뒤 원래 등록 요청을 같은 UUID로 재전송하면 게시판 비교 때문에 409입니다.
- 앱의 구버전(연결 없이 인증글 등록)은 V84 배포 후 400을 받습니다. 피드는 앱에서 내부 채널에만 열려 있어 스토어 사용자 영향은 없다는 전제입니다.

## 자유글 미니게임 기록 카드 (V89)

제품 오너 결정(2026-10-14): 랭킹 미니게임에서 개인 최고 기록을 세우고 그 게임을 10판 이상 끝낸 사용자에게 앱이 자유게시판 공유를 제안합니다. 글에는 앱이 직접 그리는 기록 카드(게임·점수·작성자 본인 순위)가 붙습니다. 숫자는 클라이언트가 보내지 않고 서버가 판 기록에서 읽어 고정합니다. 10판 조건과 제안 시점 판단은 앱 몫이며 서버는 게시 조건으로 검사하지 않습니다(완료 응답의 `playCount`만 제공).

- 등록 요청: `minigameRecord: { runId }`(`FeedMinigameRecordRequest`, 선택). 자유게시판 전용입니다. `boardType`을 생략하고 이 값을 보내면 `FREE`로 등록합니다(기록이 없을 때의 생략 기본값 `VERIFICATION`은 그대로). `VERIFICATION`과 함께 보내거나 `routineCompletion`과 함께 보내면 `FEED_INPUT_INVALID`(400)입니다. 본문 규칙은 바뀌지 않아 사진 없는 기록 글도 본문이 필요합니다(앱은 대체 문구를 항상 보냄).
- 검증(`FeedMinigameRecords`): 판이 작성자 본인 것이고 완료(`finished_at` 존재)돼야 합니다. 완료된 판은 서버 리플레이 검증을 통과한 판뿐입니다. 없는 판·남의 판·끝나지 않은 판은 판 존재 여부를 드러내지 않게 모두 `FEED_MINIGAME_RECORD_INVALID`(400, "연결할 수 없는 게임 기록입니다.")입니다. 같은 판을 여러 글에 연결하는 것은 막지 않습니다.
- 스냅샷: 게시 시점에 `gameCode`, `score`(그 판의 검증 점수), `rank`(작성자의 그 게임 현재 최고점 기준 순위 = 최고점보다 높은 활성 회원 수 + 1, 랭킹 화면과 같은 공동 순위 기준, 최고점 행이 없으면 null), `personalBest`(그 판 점수가 게시 시점 작성자 최고점과 같은지), `achievedAt`(판 완료 시각)을 `feed_posts`의 `minigame_*` 컬럼에 저장합니다. 이후 랭킹이 바뀌어도 카드 값은 바뀌지 않습니다. 판 행은 탈퇴 정리로 지워질 수 있어 FK를 두지 않습니다.
- 재시도: 기록이 있으면 요청 hash에 `\u0000minigame:{runId}`를 덧붙여 같은 UUID로 다른 판을 보내면 409입니다. 기록 없는 요청의 hash 형식은 그대로입니다.
- 수정: `FeedUpdateRequest`에는 기록 필드가 없어 수정으로 붙이거나 바꿀 수 없습니다. 기록이 있는 글을 `VERIFICATION`으로 옮기면 `FEED_INPUT_INVALID`입니다. 그 밖의 수정은 기록을 유지합니다.
- 응답: 목록·상세·등록·수정의 `FeedPostResponse.minigameRecord`(`FeedMinigameRecordResponse`, 없으면 null) = `{ gameCode: string, score: int, rank: int|null, personalBest: boolean, achievedAt: ISO instant }`.
- 삭제·탈퇴: 루틴 연결과 같게 글 삭제와 탈퇴자 글 정리(`eraseWithdrawn`) 때 카드 값 컬럼을 비웁니다. `minigame_run_id`는 요청 hash처럼 재시도 판정용으로 남깁니다.

## 구조

- `domain.feed`: 게시물·사진·좋아요·댓글 entity/repository, `V79__add_feed.sql` 신규 4개 테이블.
- `userapi.feed`: REST API, 활성 회원 guard, 조회/쓰기 서비스, 사진 변환·저장·정리.
- 조회는 작성자를 fetch join하고 사진·좋아요 수·댓글 수·내 좋아요를 페이지 단위로 일괄 읽습니다. 컬렉션 fetch join에 pagination을 걸지 않습니다.
- 쓰기는 `READ_COMMITTED`에서 행 잠금과 unique 제약을 함께 씁니다. 기본 잠금 순서는 요청 사용자 → 기존 게시물 → 사진 ID 오름차순입니다. 댓글은 알림 수신자 FK도 참조하므로 작성자, 게시물 소유자, 실제 답글 대상 작성자를 ID 오름차순으로 먼저 잠근 뒤 게시물을 잠급니다. 사진 표시 순서는 요청 순서로 따로 저장합니다.
- 사용자 잠금은 탈퇴와 등록을 직렬화합니다. 게시물 잠금은 삭제와 좋아요·댓글 요청을 직렬화합니다. 재시도 UUID와 최초 요청 SHA-256은 수정 후에도 바꾸지 않습니다.
- 기존 루틴/집/보상 서비스를 호출하거나 자동 공유하지 않습니다. 댓글은 공용 NotificationService를 같은 트랜잭션에서 호출합니다.

## 사진 수명

1. 실제 JPEG/PNG를 디코딩해 용량·치수 제한을 검증하고, EXIF 회전 보정 후 최대 긴 변 1,600px JPEG로 다시 인코딩합니다. 원본 metadata는 복사하지 않습니다. 회전 유틸은 가구 이미지 경로와 `common.image.PhotoOrientation`을 공유합니다.
2. 업로드 전에 비공개 key를 `ready=false`, 만료 24시간으로 DB에 커밋합니다. S3 I/O는 이 트랜잭션 밖에서 수행하고 전송 API 호출은 30초로 제한합니다.
3. 전송 후 별도 트랜잭션으로 회원·사진 상태를 다시 확인하고 `ready=true`로 바꿉니다. 전송 실패/프로세스 종료/중간 탈퇴에도 key가 남아 나중에 정리할 수 있습니다. 미완료 row를 즉시 삭제하지 않아 아직 진행 중인 전송과 충돌하지 않습니다.
4. 게시 시 사진을 잠가 소유권·완료·유효기간·미사용을 검증합니다. 이미지 조회는 공개 CDN 대신 JWT API를 통하고 `private, no-store`로 응답합니다.
5. `FeedCleanupJob`은 탈퇴자의 텍스트·반응을 정리하고, 미게시 만료 사진 및 삭제된 글/탈퇴자의 완료 사진을 ID cursor로 100개씩 처리합니다. 여러 인스턴스가 실행해도 이미지 잠금 안에서 상태를 재확인합니다. 저장소 삭제 실패 시 row를 유지해 다음 실행에서 재시도합니다.
6. 사진 삭제는 S3의 정확히 같은 key에 대한 모든 object version과 delete marker를 제거합니다. 공개 CDN 경로에는 사진을 넣지 않습니다.

삭제·탈퇴는 신규 요청의 노출을 즉시 차단합니다. 이미 전송된 응답까지 회수하지는 않습니다. 삭제 기록의 ID·작성자 ID·UUID·hash는 재시도 방지용으로 남으며 보존기간은 spec의 열린 질문입니다.

설정: `feed.cleanup.enabled` 기본 true, `feed.cleanup.delay-ms` 기본 300000. 최초 대기/실행 완료 후 대기에 같은 값을 씁니다. 정리 실패는 `피드 이미지 정리 실패 - imageId=...` 로그로 확인합니다. S3 실패가 누적되면 DB row와 실제 객체가 남으므로 로그·저장량을 점검해야 합니다.

## 배포 순서와 롤백

1. 최신 main의 Flyway 번호와 V79 충돌 여부를 다시 확인합니다. 이미 적용한 migration은 수정하지 않습니다.
2. `deploy/terraform/ec2/main.tf`의 EC2 앱 역할에 `private/feed/*` 읽기/쓰기/삭제/version 삭제 및 해당 prefix의 `ListBucketVersions` 권한을 적용합니다. Terraform 변경은 이 구현 작업에서 **운영 적용하지 않았습니다**. 공개 S3/CloudFront allowlist에는 이 prefix를 추가하지 않습니다.
3. 새 user-api의 Flyway migrate가 실행되는 단계에서 V79를 적용한 다음 새 domain 스키마를 validate하는 batch/worker를 갱신합니다. 배포 방식상 batch가 먼저 시작한다면 라우팅하지 않은 user-api 또는 별도 migration 단계가 선행해야 합니다.
4. 새 `FEED_COMMENT`/`FEED` enum을 포함한 user-api·admin-api·batch를 함께 갱신합니다. 구버전 읽기 프로세스가 남은 동안 피드 댓글/설정 API를 외부에 개방하지 않습니다. 새 값이 저장된 뒤의 롤백은 해당 enum을 인식하는 호환 패치 이미지로 수행합니다(운영 데이터를 임의 삭제하지 않음).
5. 두 계정으로 업로드 → 게시 → 타 계정 이미지 조회 → 좋아요·댓글 → 삭제 후 404 → 정리 후 S3 version 소멸을 확인합니다. 이것은 실제 IAM/S3 검증이며 로컬 mock 저장소 테스트로 대체하지 않습니다.
6. V79는 새 테이블만 추가하므로 기존 테이블을 변경하지 않습니다. 다만 댓글 알림의 새 enum 값은 구버전 앱이 해석할 수 없으므로 단순 구버전 롤백은 안전하지 않습니다. 롤백 중에는 피드 API와 정리 작업이 중단됩니다. 업로드된 객체/테이블을 임의로 삭제하지 않고 재배포 후 정리를 재개합니다.

## 검증

```bash
./gradlew compileJava test
git diff --check
terraform fmt -check deploy/terraform/ec2/main.tf
```

`FeedIntegrationTest`는 MySQL Testcontainers에서 실제 HTTP/JWT와 서비스 트랜잭션을 사용하고, S3 어댑터와 FCM 제출기를 대체합니다. 등록부터 삭제까지의 흐름, 소유권, 재시도·동시 요청, 삭제 경합, 실제 회원탈퇴, 만료/취소/삭제 실패 재시도, 업로드 중 탈퇴, 봇·금칙어·입력 제한을 확인합니다. 사진 디코딩·축소·EXIF 제거와 S3 요청 scope/버전 삭제는 별도 테스트로 검증합니다.

프론트 UI, 운영 IAM 적용, 실 S3 smoke, 운영 배포, 신고·차단·운영자 숨김은 이 구현 완료 주장에 포함되지 않습니다.

## 댓글 알림

`FEED_COMMENT`를 `FEED` 설정 그룹에 추가하고 `NotificationMessages.feedComment()`로 수신자 언어에 맞는 고정 안내를 만듭니다. 본인 댓글과 기존 UUID 재시도는 발송하지 않습니다. 댓글과 알림 내역은 함께 저장되므로 알림 저장 실패는 댓글도 롤백합니다. `ALL` 또는 `FEED` off이면 push만 `BLOCKED`로 끝나고 알림 내역은 유지됩니다.

`notification.ref_id`는 게시물 ID이며 알림 목록에 nullable `refId`를 노출합니다. 피드 FCM data에는 `type=FEED_COMMENT`, `notificationId`, `postId` 문자열을 넣습니다. 기존 공용 알림은 종전 payload를 유지합니다. 새 DB migration은 필요 없지만 기존 VARCHAR enum 컬럼의 값 집합은 확장됩니다.

알림에 닉네임·댓글 원문을 복사하지 않으며, 삭제 이후 남아 있는 일반 안내는 상세 조회의 404로 처리합니다. 동시 댓글의 알림 FK 잠금 교차를 막기 위해 댓글 작성자와 게시물 소유자를 ID 순서로 잠급니다. 테스트는 MySQL 커밋/롤백, 교차 댓글 경합, 재시도, 설정, 언어, 푸시 data 전달까지 검증하고 실제 기기 FCM 수신은 별도 배포 smoke입니다.


## 대댓글 (V93)

사용자의 대댓글 추가 요청에 따라 기존 일반 댓글 계약을 확장합니다. UI 표시는 한 단계이며, 답글에 다시 답해도 최초 원댓글로 묶고 실제 대상을 따로 보존합니다. 이 절은 구현된 서버 계약이며 앱 화면 연결과 AI 자동 대댓글 정책 적용은 별도입니다.

### 작성과 조회

기존 `POST /api/v1/feed/posts/{postId}/comments`에 선택 필드 `replyToCommentId`를 추가합니다. 생략/null은 기존 일반 댓글입니다.

```json
{
  "clientCommentId": "c0e16543-cd6d-46a0-ae6e-8d82bf345c43",
  "content": "이 댓글에 대한 답글",
  "replyToCommentId": 301
}
```

- 서버는 같은 게시물의 삭제되지 않은 댓글만 대상으로 허용합니다. 없는 댓글, 다른 글의 댓글, 탈퇴한 작성자, 요청자가 차단한 댓글/원댓글은 모두 `404 FEED_COMMENT_NOT_FOUND`입니다. 양수가 아닌 ID는 400입니다.
- 응답은 기존 필드와 함께 `parentCommentId`(원댓글), `replyToCommentId`(실제로 답한 댓글), `deleted`, `replyCount`를 반환합니다. 일반 댓글의 두 ID는 null입니다. 답글에 다시 답하면 `parentCommentId`는 유지되고 `replyToCommentId`만 새 대상을 가리킵니다. 답글 자체의 `replyCount`는 0이며 원댓글에 모든 답글 수를 집계합니다.
- 일반 댓글의 기존 요청 hash는 바꾸지 않습니다. 답글은 대상 ID도 재시도 판정에 포함하며 같은 UUID로 본문이나 대상을 바꾸면 409입니다. 성공한 답글을 동일 UUID로 재전송할 때 대상만 삭제·탈퇴했다면 기존 답글을 반환하고 새 알림은 생성하지 않습니다. 답글 자체가 삭제됐으면 409입니다.
- `GET .../comments`는 기존처럼 살아 있는 댓글과 답글 전체를 ID 오름차순으로 반환합니다.
- `GET .../comments?topLevelOnly=true`는 원댓글만 페이지로 반환합니다. 원댓글의 `replyCount`는 요청자가 볼 수 있는 살아 있는 답글 수입니다. 클라이언트는 답글 작성/삭제 후 해당 원댓글의 개수와 답글 목록을 갱신합니다.
- `GET /api/v1/feed/posts/{postId}/comments/{commentId}/replies`는 지정한 **원댓글**의 모든 답글을 ID 오름차순으로 페이지 조회합니다. 대댓글 ID를 원댓글 자리에 보내면 404입니다. 두 목록 모두 기존 `cursor`, `size`(기본 20, 최대 50), `nextCursor`, `hasNext` 규칙을 따릅니다. 늦게 추가된 답글은 원댓글 페이지를 다시 가져오는 것만으로 나타나지 않으므로 해당 답글 목록을 갱신합니다.

### 삭제, 탈퇴, 차단, 개수

- 본인 댓글/답글만 기존 DELETE API로 삭제합니다. 원댓글을 지워도 다른 사람의 답글은 유지합니다. 답글이 남은 원댓글은 `topLevelOnly=true`에서 `deleted=true`, `content=""`, `mine=false`, `author={userId:null,nickname:null,profileImageKey:null}`인 자리로 반환합니다. 앱은 이때 삭제 안내를 표시하며 작성자 프로필 이동과 답글 작성을 제공하지 않습니다. 답글에 대한 답글은 계속 작성할 수 있습니다.
- 원댓글 작성자가 탈퇴해도 같은 익명 자리를 사용하고 본문/작성자 정보를 노출하지 않습니다. 남은 답글이 없으면 자리도 목록에서 제외합니다. 구버전 전체 댓글 목록은 삭제 자리를 반환하지 않습니다.
- 삭제·탈퇴한 답글은 목록과 개수에서 제외하며, 다른 답글에 저장된 그 대상 ID는 유지할 수 있습니다. 앱은 대상 댓글이 없을 때 작성자나 본문을 추정하지 않습니다. 물리 파기 시 실제 대상 FK만 null로 바뀔 수 있으며 원댓글 연결은 유지합니다.
- 요청자가 차단한 원댓글의 대화는 전체 목록, 원댓글 목록, 답글 목록, 게시물 댓글 수에서 함께 숨깁니다. 답글 작성자만 차단한 경우 해당 답글만 숨깁니다.
- 게시물 `commentCount`는 보이는 원댓글과 답글의 합계입니다. 삭제 안내 자리는 포함하지 않습니다. 원댓글 `replyCount`도 같은 차단/탈퇴/삭제 조건으로 계산합니다.
- 게시물 삭제는 기존처럼 댓글 전체를 제거하고 모든 댓글 조회/작성 경로를 404로 닫습니다. V93의 부모 FK는 물리 삭제 시 하위 답글을 함께 정리합니다. 회원탈퇴 정리는 기존 soft delete/내용 파기를 유지합니다.

### 알림과 배포

- 새 답글은 게시물 작성자에게 기존 댓글 알림을, 실제 대상 댓글 작성자에게 답글 알림을 저장합니다. 두 사람이 같으면 답글 알림 하나만 저장합니다. 본인 수신과 수신자가 작성자를 차단한 경우는 제외합니다.
- 기존 `FEED_COMMENT` 타입, `FEED` 설정, `refId=postId`와 푸시의 게시물 이동 경로를 유지합니다. 답글 수신자의 언어로 한국어 `답글이 달렸어요` / `내 댓글에 답글이 달렸어요. 확인해 보세요!`, 영어 `New reply` / `Someone replied to your comment. Take a look!`를 사용합니다. 내용이나 닉네임은 알림에 복사하지 않습니다.
- 댓글과 모든 수신자의 알림은 같은 트랜잭션이며 하나라도 저장 실패하면 함께 롤백합니다. 푸시는 기존 커밋 후 경로를 따릅니다.
- V93는 nullable 열과 FK/인덱스를 추가합니다. 기존 댓글은 일반 댓글로 유지하고 기존 UUID/hash를 보존합니다. 새 API 서버 배포 후 앱에서 대댓글 입력과 원댓글/답글 조회를 연결해야 합니다. 앱 UI를 연결하기 전에는 기능을 화면에 제공한 것으로 보지 않습니다.
