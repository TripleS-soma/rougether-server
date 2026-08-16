# 미접속 사용자의 끊긴 스트릭이 과거 값으로 노출됨

- 확인일: 2026-08-16
- 영향 범위: 오늘 현황, 내 방, 같은 집 구성원의 방, 루틴 완료·취소 응답, 주간 회고 통계·LLM 프롬프트
- 상태: 코드 수정 및 회귀 테스트 완료, 배포 전

## 증상

사용자가 4일 연속 루틴을 성공한 뒤 하루를 완전히 건너뛰어도, 다른 구성원이 그 사용자의 방을 조회하면 `currentCount=4`가 계속 노출됐다. 해당 사용자가 이후 다시 루틴을 성공하면 그제야 `currentCount=1`로 재시작했다.

여기서 스트릭은 접속 일수가 아니라 **KST 기준 루틴 연속 성공일**이다. 로그인·토큰 갱신은 `lastAccessedAt`만 갱신하며 스트릭을 변경하지 않는다.

## 재현 조건

1. 마지막 성공일이 KST 오늘보다 이틀 전인 스트릭을 준비한다.
2. DB에는 `current_count=4`, `longest_count=4`, `last_success_date=오늘-2일`이 저장돼 있다.
3. 다른 집 구성원이 `GET /api/v1/houses/{houseId}/members/{membershipId}/room`을 호출한다.
4. 수정 전 응답은 저장된 값을 그대로 사용해 `streak.currentCount=4`를 반환한다.

기대값은 다음과 같다.

```json
{
  "streak": {
    "currentCount": 0,
    "longestCount": 4
  }
}
```

`lastSuccessDate`와 DB의 `current_count=4`는 이력과 다음 성공 계산을 위해 보존한다. 사용자가 오늘 루틴을 성공하면 기존 `Streak.applySuccess(today)`가 비연속 상태를 감지해 저장값을 `1`로 바꾼다.

## 원인

`streaks.current_count`가 두 역할을 동시에 맡고 있었다.

- 다음 성공 시 `+1` 또는 `1` 재시작을 판정하기 위한 저장 상태
- API에 바로 노출하는 현재 표시값

하지만 날짜가 지나 스트릭이 끊기는 시점에는 DB 쓰기가 없었다. `current_count`는 다음 당일 루틴 성공 때만 재평가됐고, 조회 DTO는 이 저장값을 그대로 반환했다.

직접 문제가 발생한 경로는 다음과 같다.

```text
HouseMemberActivityService.getMemberRoom
  -> RoomQueryService.getRoomOf
  -> RoomResponse.RoomStreakResponse.of
  -> streak.getCurrentCount()  // 날짜 판정 없이 과거 저장값 노출
```

같은 방식이 `/api/v1/today`, `/api/v1/rooms/me`, 방 저장 응답과 루틴 완료·취소 응답에도 남아 있었다. 주간 회고 배치의 `WeeklyStatsAggregator`도 저장값을 그대로 통계 JSON과 LLM 프롬프트에 넣고 있었다.

## 해결

[`Streak.currentCountOn(referenceDate)`](../../domain/src/main/java/com/triples/rougether/domain/routine/entity/Streak.java)을 공통 표시 정책으로 추가했다.

```text
lastSuccessDate < referenceDate - 1일  -> 0
그 외(오늘 또는 어제 성공)             -> 저장된 currentCount
```

서비스 계층에서 `Asia/Seoul` 기준일을 구한 뒤 방·오늘 현황·루틴 응답 DTO에 전달한다. 주간 회고 배치는 주입된 KST `Clock`으로 회고 생성일을 구해 같은 정책을 적용한다. 이에 따라 사용자가 다시 접속하거나 별도 정리 배치가 실행되지 않아도 조회·회고 생성 시점의 값이 정확하다.

마지막 성공일이 어제라면 오늘은 아직 스트릭을 이어갈 수 있으므로 기존 값을 유지한다. 오늘도 성공하지 않은 채 다음 KST 날짜가 되면 표시값은 0이 된다.

## 배치에서 DB를 0으로 바꾸지 않은 이유

배치만으로 해결하면 작업 지연·실패 시 같은 과거 값이 다시 노출된다. 조회 시 투영은 사용자에게 보이는 정합성을 배치 상태와 분리한다.

또한 저장된 `current_count`와 `longest_count`를 유지하면 기존 성공 처리 로직과 최장 기록을 변경하지 않아도 된다. `status`, `last_evaluated_date`까지 영속적으로 정리하는 배치는 별도 요구사항으로 다룬다.

## 회귀 테스트

- `StreakTest`: 오늘·어제 경계 유지, 하루를 완전히 건너뛴 뒤 표시값 0, 저장값 보존
- `HouseMemberActivityIntegrationTest`: 타인이 끊긴 구성원의 방을 조회할 때 0 노출
- `TodayServiceIntegrationTest`: 오늘 현황에도 같은 정책 적용
- `RoutineCompletionServiceIntegrationTest`: 과거 완료 응답은 0으로 보이되 DB 저장값은 유지
- `RoutineCancelServiceIntegrationTest`: 과거 완료 취소 응답도 0으로 보이되 DB 저장값은 유지
- `WeeklyStatsAggregatorTest`: 회고 생성일에 만료된 값은 0, 전날까지 이어진 값은 유지
- `WeeklyReportJobIntegrationTest`: 저장값 2가 끊긴 경우 통계 JSON과 LLM 프롬프트 모두 0 사용

수정 전에는 새 타인 방 회귀 테스트가 `expected: 0, actual: 4`로 실패했다. 수정 후 다음 검증을 통과했다.

```bash
./gradlew :domain:test --tests 'com.triples.rougether.domain.routine.StreakTest'

./gradlew :user-api:test \
  --tests 'com.triples.rougether.userapi.house.HouseMemberActivityIntegrationTest' \
  --tests 'com.triples.rougether.userapi.today.service.TodayServiceIntegrationTest' \
  --tests 'com.triples.rougether.userapi.routine.service.RoutineCompletionServiceIntegrationTest' \
  --tests 'com.triples.rougether.userapi.routine.service.RoutineCancelServiceIntegrationTest' \
  --tests 'com.triples.rougether.userapi.room.RoomQueryServiceTest'

./gradlew :batch:test \
  --tests 'com.triples.rougether.batch.weeklyreport.WeeklyStatsAggregatorTest' \
  --tests 'com.triples.rougether.batch.weeklyreport.WeeklyReportJobIntegrationTest'

./gradlew test
git diff --check
```

## 남은 스펙 확인 사항

현재 정본 spec에는 끊긴 스트릭의 만료 트리거와 `status`·`last_evaluated_date` 전이 규칙이 구체적으로 정의돼 있지 않다. 다음 확장 전에 아래 항목을 정본에 확정한다.

- 루틴 비예정일도 연속 성공일을 끊는지
- 영속 상태를 정리하는 day-end 배치가 필요한지
- `status`, `last_evaluated_date`의 의미와 전이 시점
