# 가구 생성 결과의 지급과 이미지 교체 분리

2026-10-19 | R6 2차 | 기준 main `fc81092bf3fa1d963458d089939c1c97ec7d8b9e`

검수를 통과한 가구를 보관함에 지급하거나 기존 가구의 이미지를 교체하는 코드를 `FurnitureResultItemService`로 옮겼다. 검수 흐름을 읽을 때 아이템 생성자의 상세 필드와 저장소 쿼리까지 함께 따라가야 했던 부분을 분리한 작업이다.

## 변경 전과 후

| 책임 | 변경 전 | 변경 후 |
| --- | --- | --- |
| 최초 가구 지급 | `reviewed`가 테마 조회, Item 생성, UserItem 저장을 직접 처리 | `FurnitureResultItemService.grant` |
| 기존 가구 이미지 교체 | `reviewed`가 보유 아이템 조회와 조건부 UPDATE를 직접 처리 | `FurnitureResultItemService.replaceImage` |
| 검수 판단과 오류 처리 | `FurnitureGenerationTransactions` | 유지 |
| 생성권 정산과 작업 성공 기록 | `FurnitureGenerationTransactions` | 유지 |
| 사용자와 작업 잠금, lease 확인 | `FurnitureGenerationTransactions` | 유지 |

```text
FurnitureGenerationTransactions.reviewed
  사용자와 작업 잠금, lease 확인
  검수 기록, ACCEPT와 이미지 품질 확인
  ├─ 최초 지급 → resultItems.grant
  └─ 기존 가구 → resultItems.replaceImage
                 └─ 외부 변경 감지 시 기존 실패 처리
  생성권 정산 → 작업 성공 기록

위 전체 흐름은 하나의 트랜잭션으로 커밋하거나 롤백한다.
```

새 서비스는 `furniture-application`에 두어 사용자 API, 상주 워커, Lambda Control에서 함께 사용한다. 메서드에는 필요한 사용자 ID, 보유 ID, 이름, 이미지 키만 전달한다. 작업 엔티티와 AI 검수 응답 전체를 새 서비스에 넘기지 않는다.

새 서비스의 `@Transactional(propagation = MANDATORY)`는 호출자의 트랜잭션 참여를 강제한다. 지급을 먼저 별도로 커밋하지 않으므로 뒤이은 생성권 정산이 실패해도 아이템과 보관함 저장을 함께 되돌릴 수 있다. 명시적으로 빈을 등록하는 Lambda Control의 `@Import`에도 새 서비스를 추가한다.

기존 실행 서비스의 직접 생성자 의존성은 **12개에서 10개**로 바뀐다. 지급 전용 저장소 3개를 제거하고 결과 아이템 서비스 1개를 추가한 값이다. 새 서비스는 저장소 4개를 사용하며, 전체 시스템의 의존성이 그만큼 사라졌다는 뜻은 아니다. 지급 방식과 검수 흐름의 변경 이유를 분리하는 데 목적이 있다.

## 유지하는 동작

| 상황 | 보존 기준 |
| --- | --- |
| 최초 지급 | `photo_furniture` 테마, `furniture`, `positioned`, 검수 이름과 결과 키, 기본 슬롯 `midRight`. 가격과 구매 통화는 null, limited와 active는 false |
| 재검수 성공 | 같은 Item과 UserItem을 유지하고 이미지 키만 교체. 이름 등 다른 필드는 유지 |
| 외부에서 이미지 수정 | 기존 결과 키와 DB 키가 같을 때만 교체. 불일치하면 `RESULT_CHANGED_EXTERNALLY`를 기록하며 외부 수정은 보존 |
| 생성권 | 지급 후 정산, 정산 후 성공 기록. 완료된 가구의 피드백은 추가 소비나 반환 없음 |
| 저장 또는 정산 예외 | 검수 트랜잭션을 롤백한 뒤 기존 워커의 실패 처리에서 예약 반환 |
| 중복 처리 | 기존 사용자·작업 잠금과 lease, Lambda 실행 소유권 검사 유지 |

CAS 충돌은 예외로 바꾸지 않는다. 기존처럼 실패 상태를 저장한 뒤 false를 반환해야 그 실패 기록이 커밋된다. API, DTO, 엔티티, 저장소 쿼리, DB migration과 외부 AI 호출 방식은 바꾸지 않는다.

원본 소스와 대조해 `reviewed`, import, 의존성 필드 이외의 `FurnitureGenerationTransactions` 내용은 동일함을 확인했다. 성능이나 SQL 수를 개선한 작업은 아니다.

## 검증 기록

변경 전 기존 범위는 사용자 API의 가구 생성·정산 102개와 Lambda Control 2개를 합쳐 104개였다. 44초에 99개 통과와 기존 환경 조건부 5개 생략, 실패·오류 0개를 확인했다. 검증을 보강한 뒤에도 원본 생산 코드에서 105개 중 100개 통과와 동일 5개 생략을 확인했다. 보강 후 기준 실행은 46초였으며, 이 결과를 확보한 다음 생산 코드를 분리했다. 두 기준 실행에서 사용자 API는 재실행했고 Lambda Control은 입력이 같아 기존 결과를 재사용했다.

| 검증 범위 | 변경 전 | 변경 후 |
| --- | --- | --- |
| 기존 관련 104개 | 99개 통과, 5개 생략 | 동일 결과 |
| 보강한 관련 105개 | 100개 통과, 5개 생략 | 동일 결과 |
| 전체 모듈 컴파일과 테스트 | 변경 전 전체 실행은 하지 않음 | 컴파일 성공, 2,741개 통과, 기존 10개 생략 |
| `git diff --check`와 별도 코드 리뷰 | diff 검사 통과 | diff 검사 통과, 정합성·인가·트랜잭션 리뷰 PASS |

최종 `./gradlew compileJava test --console=plain`은 **4분 38초**, 종료 코드 0과 `BUILD SUCCESSFUL`로 완료됐다. 모든 최종 결과의 실패·오류는 0개다. JUnit XML 391개를 집계했고, 실행 항목의 중복을 보존한 비교에서 관련 105개의 이름과 통과·생략 결과가 전후 동일했다. 기존 104개에서 추가된 항목은 정산 실패 롤백 테스트 1개뿐이다.

| 최종 집계 | 전체 | 통과 | 생략 |
| --- | ---: | ---: | ---: |
| 사용자 API — 전체 재실행 | 2,126 | 2,119 | 7 |
| 가구 Lambda AI·Control·워커 — 재실행 | 9 | 9 | 0 |
| 나머지 모듈 — 입력이 같아 기존 결과 재사용 | 616 | 613 | 3 |
| 합계 | **2,751** | **2,741** | **10** |

54개 Gradle 태스크 중 14개를 실행하고 40개는 `UP-TO-DATE` 결과를 재사용했다. 관련 104개와 105개는 전체 합계에 포함되므로 중복 합산하지 않는다. 건수는 커버리지 비율이 아니며 실행 시간은 성능 개선의 근거가 아니다.

이번에 보강한 검증은 다음 세 가지다. 기존 검증을 삭제하거나 완화하지 않았다.

| 테스트 | 보강 내용 |
| --- | --- |
| `FurnitureGenerationIntegrationTest` — 기존 테스트 보강 | DB에 저장된 최초 지급 가구의 테마, 배치, 이름, 이미지 키, 가격과 활성 여부, 기본값과 nullable 필드, 보유자 연결 |
| `FurnitureBillingIntegrationTest` — 1개 추가 | 보관함 저장 후 SPEND 원장 저장을 실패시켜 Item과 UserItem 롤백, 작업 실패, 생성권 예약 반환 확인. 보관함 저장 다음 SPEND 저장이 호출됐는지도 검증 |
| `FurnitureLambdaIntegrationTest` — 기존 테스트 보강 | 커밋 응답 유실과 재전달에도 사용자 보관함이 정확히 1개이며 작업의 보유 ID와 일치 |

기존 동일 보유 ID 이미지 교체, 외부 수정 보호, 보관함 저장 실패, 권한과 lease 검사도 함께 검증한다. 실제 Lambda Control과 워커 컨텍스트 부팅 테스트로 새 서비스의 빈 등록을 확인한다. 별도 코드 리뷰에서 정합성·인가·트랜잭션 문제는 발견하지 못했다.

Java 25와 MySQL 8.4 Testcontainers 환경에서 검증했다. 생략 10개는 기존의 실제 가구 생성·개발 Lambda 5개, 실제 AI 사용자 모델 평가 2개, 실제 FastAPI 1개, 네이티브 libvips 2개다. 이번 작업으로 비활성화한 테스트는 없다. AI와 스토리지는 mock을 사용하므로 유료 AI 호출이나 운영 배포 검증을 뜻하지 않는다.

2026-10-19 커밋·병합 준비 시 원격을 갱신해 `origin/main`이 위 검증 기준 커밋과 같음을 확인했다. 최신 migration은 V93이며 이번 변경에는 migration 추가·수정이 없다. 최종 CI와 병합 상태는 해당 PR 이력으로 확인하며 운영 배포 완료는 별도로 구분한다.

```bash
# 변경 전 회귀 기준
./gradlew :user-api:test \
  --tests 'com.triples.rougether.userapi.furniture.*' \
  --tests '*FurnitureBillingIntegrationTest' \
  :furniture-lambda-control:test --console=plain

# 변경 후 전체 검증
./gradlew compileJava test --console=plain
git diff --check
```

## 계약 확인

스펙 저장소의 최신 원격 추적 main `a0d7545b2d83403ccbe770f8320a856c4611a1dc`에서 `domains/attendance`, `domains/market`, 루트 API와 ERD를 확인했다. 성공 시 본인 보관함 지급, 성공·실패에 따른 생성권 정산, 중복 정산 무시, 재검수 실패 시 기존 가구 보존을 유지한다.

기본 슬롯, 생성자 세부 값, 동일 보유 ID 유지 방식, CAS 실패 코드와 처리 순서는 스펙에 모두 명시되어 있지는 않다. 이번 작업에서는 현재 구현과 회귀 테스트를 기준으로 보존하며 새 정책으로 확정하지 않는다.
