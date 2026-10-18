# 방 응답 조립 리팩토링

2026-10-14 · 기준 main `b2e22552169542281664bad1dbf2d36f3e9e3149`

방 저장과 조회에 중복된 단일 방 응답 조립을 `RoomResponseReader`로 모았다. 응답 필드를 추가하거나 조회 방식을 바꿀 때 한곳에서 관리할 수 있다.

## 변경 전후

| 비교 항목 | 변경 전 | 변경 후 |
| --- | --- | --- |
| 단일 방 응답 조립 | Command와 Query의 `assemble`에 같은 처리가 중복됨 | `RoomResponseReader.read` 한곳을 네 호출 경로가 공유함 |
| 저장 서비스의 직접 의존성 | 9개 | 5개. 응답용 조회 의존성을 Reader로 이동함 |
| 응답 변경 시 수정 위치 | 두 서비스의 조립 코드를 함께 수정해야 함 | 공통 Reader와 기존 DTO에서 관리함 |
| 저장 후 응답 검증 | 같은 영속성 컨텍스트에서 일부 필드를 비교함 | 실제 커밋 후 새 트랜잭션의 전체 응답 비교 4개 추가 |
| 추가 보호 | 기존 조회 테스트 | 타인 방 미생성과 여러 방 일괄 조회 회귀 테스트 2개 추가 |

응집도 개선 근거는 저장 처리와 응답 조회의 변경 이유를 분리한 것이다. 조회 수나 응답 속도 개선은 측정하지 않았으므로 성능 개선으로 해석하지 않는다. 아래 테스트 결과는 2026-10-14 실행 기록이며, 이 비교표를 보강한 2026-10-18에 다시 실행한 결과는 아니다.

## 변경 범위

| 호출 경로 | 호출자가 유지하는 책임 | Reader가 담당하는 책임 |
| --- | --- | --- |
| `updateSlots`, `updateLayout` | 방 생성·잠금, 소유 검증, revision, 배치 저장 | 확보한 방의 슬롯·배치·스트릭·캐릭터·악세사리·렌더·거미줄 조회와 `RoomResponse` 조립 |
| `getMyRoom` | 없는 방의 지연 생성 | 위와 동일 |
| `getRoomOf` | 없는 방의 오류, 읽기 전용 조회 | 위와 동일 |

`RoomResponseReader`는 `MANDATORY`로 기존 트랜잭션에 참여한다. 방을 다시 생성하거나 잠그지 않고, 호출자가 확보한 `PersonalRoom`을 사용한다. 기존 조회 순서와 저장 직후 auto flush를 유지한다.

`RoomCommandService`의 직접 의존성은 9개에서 5개로 줄었다. 여러 방의 공개 렌더를 만드는 `findRendersOf`는 기존 일괄 조회를 유지한다. API/DTO·권한·에러·DB 스키마·성장 정책은 변경하지 않았다.

## 사전 점검 이후 바뀐 기준

사전 리포트 기준 `33419df8` 이후 main의 PR #442에서 모루 레벨 보상이 제거되고 캐릭터 레벨 10 해금이 추가됐다. 이번 리팩토링은 최신 동작을 보존한다. 조회에 보상 지급을 재도입하지 않으며 `characterUnlocked`, `characterUnlockLevel`도 기존 DTO 계산을 그대로 사용한다.

## 보강한 회귀 테스트

`RoomResponseConsistencyIntegrationTest`는 테스트 전체에 트랜잭션을 걸지 않는다. 각 서비스의 실제 트랜잭션이 커밋된 뒤 내 방·타인 방을 새로 조회해 전체 응답을 비교한다.

- 슬롯 저장: 성장·최고 레벨 해금, 선택 캐릭터·악세사리·렌더 프로필, 스트릭, 활성 거미줄을 실제 값으로 확인한다.
- 자유배치 저장: 좌표·scale 정규화, zIndex 순서, 구버전 슬롯과 revision을 확인한다.
- 자유배치 상태의 surface 슬롯 해제: 기존 가구 배치와 방 형식이 유지되는지 확인한다.
- 빈 방 저장: 지연 생성, null·빈 배열·기본값, 빈 자유배치 전환을 확인한다.

시각 필드는 제외하지 않는다. 저장 응답과 DB 재조회 사이에는 기존 `TIMESTAMP` 초 정밀도를 고려해 1초 미만의 차이만 허용한다. 재조회 응답끼리와 재조회·DB 시각은 정확히 일치해야 한다. 테스트 JDBC 입력·조회는 Hibernate `Instant`와 같은 UTC 기준을 사용한다.

`RoomQueryServiceTest`에는 타인의 없는 방을 생성하지 않고 404로 거부하는 경우와, 여러 방에서 단건 조회 대신 일괄 조회를 사용하는 경우를 추가했다. 기존 저장·revision·구버전 호환·롤백 assertion은 유지했다.

## 재현 환경과 명령

Java 25와 Docker를 사용한다. 통합 테스트는 MySQL 8.4 Testcontainers로 실행한다.

```bash
./gradlew compileJava
./gradlew :user-api:test --tests '*RoomResponseConsistencyIntegrationTest'
./gradlew test
git diff --check
```

## 검증 결과

| 검증 | 결과 |
| --- | --- |
| 변경 전 방 관련 기존 테스트 5개 클래스 | 32개 통과 |
| 변경 후 같은 범위와 조회 회귀 테스트 | 34개 통과 |
| 새 응답 일치 통합 테스트 | 4개 통과 |
| 전체 모듈 `compileJava` | 통과 |
| 전체 `./gradlew test` | 2,613개 중 2,605개 통과, 8개 조건부 생략, 실패·오류 0개 |
| `git diff --check` | 통과 |

전체 테스트는 5분 55초가 걸렸다. 통계는 각 모듈의 `build/test-results/test/TEST-*.xml`을 합산했다. 사용자 API에서는 1,991개 중 1,986개가 통과하고 5개가 생략됐다.

생략된 테스트는 기존 환경 조건에 따른 것이다. 방 응답 테스트는 생략하지 않았다.

- libvips 네이티브 테스트 2개: `VIPS_NATIVE_PATH` 필요.
- 실제 FastAPI 계약 테스트 1개: `AI_SERVICE_CONTRACT_URL` 필요.
- 실제 가구 AI·개발 Lambda 테스트 5개: `FURNITURE_LIVE_SMOKE`, `FURNITURE_FLARE_CONCURRENT_LIVE`, `FURNITURE_LAMBDA_DEV_SMOKE` 활성화 필요.

정합성 리뷰와 테스트 충실도 검토에서도 차단할 결함은 발견되지 않았다. 이 결과는 로컬 worktree의 검증이며 운영 배포 결과는 아니다.
