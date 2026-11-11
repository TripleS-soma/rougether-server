# 게시물 작성자의 집에 참여

2026-11-11 사용자 요청 기준으로 게시물에서 집에 입주하는 경로를 추가했습니다. 게시물 작성자가 속한 집은 공개 여부와 무관하게 선택할 수 있고, 기존 바로 입주 설정이 켜진 집만 즉시 입주하고, 꺼진 집은 방장 승인 대기 신청을 생성합니다. 두 경로 모두 코드 입력은 필요 없습니다. 기존 탐색과 초대코드 경로의 계약은 유지합니다.

## 앱 연동

1. 게시물의 집 입주 버튼에서 `GET /api/v1/feed/posts/{postId}/houses`를 호출합니다. 요청 본문과 작성자 ID는 필요 없으며 인증 헤더가 필요합니다.
2. 작성자가 현재 ACTIVE 구성원인 집을 최신 생성순으로 표시합니다. 방장인 집과 일반 구성원인 집 모두 포함하며, 비공개 집도 포함합니다. 집 수와 관계없이 사용자가 목록에서 신청할 집을 고릅니다. 게시물 버튼과 참여 버튼의 문구는 입주 신청으로 통일합니다. `items=[]`이면 입주 대상 집이 없다고 표시합니다.
3. 버튼은 입주 신청으로 통일하고 `requiresApproval=false`이면 신청 즉시 입주, true이면 방장 승인 후 입주라고 안내합니다. `canJoin=true`인 집에서 `POST /api/v1/feed/posts/{postId}/houses/{houseId}/join`을 호출합니다. 요청 본문 없이 인증 헤더만 보냅니다.
4. `pendingApproval=false`이면 응답의 `houseId`로 집 화면을 엽니다. `pendingApproval=true`이면 입주 신청 완료를 안내하고 방장 승인을 기다립니다. 실제 입주가 확정된 뒤 기존 `GET /api/v1/houses/{houseId}`를 사용할 수 있습니다. 비공개 집의 기존 preview API는 비구성원에게 열려 있지 않으므로 입주 전에 preview를 호출하지 않습니다.

목록 응답:

```json
{
  "items": [{
    "houseId": 12,
    "name": "함께하는 집",
    "coverImageKey": "house/cover.png",
    "level": 2,
    "displayMode": "DIORAMA",
    "isPublic": false,
    "requiresApproval": false,
    "currentMemberCount": 1,
    "maxMembers": 4,
    "isMember": false,
    "isFull": false,
    "myMembershipStatus": null,
    "myJoinRequestStatus": null,
    "canJoin": true
  }]
}
```

`currentMemberCount`는 동거 봇을 제외한 실사용자 수입니다. `maxMembers=null`이면 무제한입니다. `myMembershipStatus`는 본인의 ACTIVE, LEFT, KICKED 이력이며 없으면 null입니다. `myJoinRequestStatus`는 PENDING 또는 REJECTED이며 이력이 없거나 이미 구성원이면 null입니다. 바로 입주 설정이 켜진 집은 대기 신청이 있어도 즉시 입주할 수 있습니다. 설정이 꺼진 집은 PENDING 중복 신청을 막습니다. 초대코드와 다른 회원의 활동 정보는 노출하지 않습니다.

바로 입주 설정이 켜진 집의 성공 응답(200):

```json
{
  "membershipId": 31,
  "houseId": 12,
  "status": "ACTIVE",
  "pendingApproval": false,
  "joinRequestId": null
}
```

설정이 꺼진 집은 `membershipId=null`, `status=null`, `pendingApproval=true`, `joinRequestId`를 반환합니다. 신청만으로 멤버십이나 구성원 수는 바뀌지 않으며, 방장에게 신청 도착 알림을 저장합니다. 기존 신청 수락과 거절 API를 그대로 사용합니다.

## 참여 판정과 오류

- 활성 일반 회원만 조회하고 입주할 수 있습니다. 탈퇴 회원과 동거 봇은 `401 AUTH_INVALID_TOKEN`으로 거절합니다.
- 삭제, 작성자 탈퇴, 요청자가 작성자를 차단한 게시물은 `404 FEED_POST_NOT_FOUND`입니다.
- 삭제된 집이나 작성자가 현재 ACTIVE 구성원이 아닌 집은 `404 HOUSE_NOT_FOUND`입니다. 임의의 비공개 집 ID를 보내는 우회를 막습니다.
- 실사용자로 만석이면 `409 HOUSE_FULL`, 본인이 강퇴된 집이면 `409 HOUSE_KICKED_MEMBER`, 이미 구성원이면 `409 HOUSE_ALREADY_MEMBER`입니다. 마지막 경우 앱은 해당 집 화면으로 이동할 수 있습니다.
- LEFT 이력은 기존 멤버십을 재활성화합니다. 즉시입주할 때 같은 집의 PENDING 신청은 ACCEPTED로 함께 종결합니다. 승인 경로의 거절 이력은 재신청할 수 있습니다.
- 집 행 락 아래 정원 판정, 동거 봇 자리 양보, 구성원 수 증가, 기존 구성원 입주 알림 저장을 한 트랜잭션으로 처리합니다. 마지막 한 자리의 동시 입주는 한 명만 성공합니다.
- 목록은 조회 시점 정보입니다. 입주할 때 작성자의 ACTIVE 멤버십과 정원을 다시 검사하므로, 실패하면 목록을 갱신합니다.

이 변경에는 DB migration이 필요하지 않습니다. 모바일 연동 작업 폴더는 `../feed-author-house-mobile`입니다. 게시물 상세의 집 선택 시트에서 설정에 따라 처리 방식을 안내하며, 신청 버튼은 입주 신청으로 통일합니다.


## 검증 기록

- 공개 여부와 바로 입주 설정의 4가지 조합, 작성자가 방장이 아닌 집의 신청과 즉시입주, 신청 알림의 실제 방장 전달, 승인 후 멤버십 생성, 강퇴와 만석 차단, 코드 없이 비공개 집 참여를 MySQL 통합테스트로 확인했습니다.
- 마지막 한 자리의 동시입주, LEFT 재활성화, 기존 신청 종결, 삭제와 차단 게시물 접근 차단을 검증했습니다.
- 신규 기능 테스트 20개와 기존 집 참여 회귀 테스트 42개가 통과했습니다. 전체 모듈 compileJava와 git diff --check도 통과했습니다.
- 전체 백엔드 test 실행은 user-api 2,329개 중 19개 실패, 6개 건너뜀이었습니다. 접속 선물 참여 테이블 FK로 테스트 회원 정리에 실패하는 문제와 기존 보안 테스트의 503은 수정 없는 main(21a27a0b)에서도 재현했습니다. 미니게임 순위 단언 실패도 있었으며 이 기능의 완료 검증과 구분합니다.
- 모바일 타입 검사, 린트, 포맷 검사와 전체 테스트(446개 스위트, 3,721개 통과)를 확인했습니다. 최종 문구 수정과 집 선택 배선도 43개 테스트로 다시 검증했습니다.
- 390x844 브라우저에서 집 선택 시트의 레이아웃을 확인했습니다. 이는 개발 갤러리 확인이며 운영 API에 배포한 결과가 아닙니다.
