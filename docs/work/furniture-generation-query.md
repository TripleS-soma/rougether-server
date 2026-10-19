# 가구 생성 조회 분리

2026-10-19 | R6 1차 | 기준 main `bede2d708659dd5cc898663071c629bcf8dfb167`

가구 생성의 목록과 상태 조회를 `FurnitureGenerationQueryService`로 옮겼다. 사용자 조회 조건을 수정할 때 접수, 실행 관리, 생성권 정산, 보관함 지급 코드를 함께 다루던 구조를 정리한 작업이다.

## 변경 전과 후

| 책임 | 변경 전 | 변경 후 |
| --- | --- | --- |
| 목록과 상태 조회 | `FurnitureGenerationService`를 거쳐 `FurnitureGenerationTransactions` 호출 | 컨트롤러가 `FurnitureGenerationQueryService` 직접 호출 |
| 조회 권한과 응답 조립 | 공용 실행 서비스의 `get`, `list`, `requireActiveUser` | 사용자 API의 조회 서비스로 함께 이동 |
| 접수 직후 상태 확인 | `submit` 끝에서 `transactions.get` 호출 | 같은 위치에서 조회 서비스 호출 |
| 접수, 실행, 정산, 보관함 지급 | 기존 실행 서비스 | 기존 메서드와 트랜잭션 유지 |
| 워커와 정리용 조회 | `due`, `maintenanceCandidates`, `referencesAsset` | 기존 실행 서비스에 유지 |

```text
변경 전
GET 요청 → FurnitureGenerationService → FurnitureGenerationTransactions

변경 후
GET 요청 → FurnitureGenerationQueryService → 사용자·작업 저장소
접수 요청 → FurnitureGenerationService → 기존 접수·업로드 처리
                                     └→ QueryService에서 최종 상태 조회
```

조회 서비스는 `user-api`의 `furniture.service`에 두며 사용자와 생성 작업 저장소 두 개만 참조한다. 이미지 처리, 스토리지, 생성 설정, 워커 실행 서비스에 의존하지 않는다. 공용 실행 서비스도 조회 서비스를 참조하지 않으므로 Lambda Control이나 워커에 사용자 API 의존성이 생기지 않는다.

각 조회 메서드의 `@Transactional(readOnly = true)`를 새 조회 클래스에 적용했다. 기존 공개 `get/list` 래퍼는 제거해 GET 요청이 쓰기 서비스를 거치지 않도록 했다. 조회 성능이나 SQL 실행 횟수를 개선한 작업은 아니다.

기준 커밋과 비교해 옮긴 `get`, `list`, `requireActiveUser`의 본문이 같음을 확인했다. 이 세 메서드를 제거한 나머지 `FurnitureGenerationTransactions` 소스도 원본과 완전히 같다. 전체 의존성 개수나 코드 줄 수를 줄이는 것보다 조회와 실행의 변경 이유를 분리하는 데 목적을 뒀다.

## 유지할 동작

| 항목 | 보존 기준 |
| --- | --- |
| 목록 API | `GET /api/v1/me/furniture-generations`, 200, `{ "items": [...] }`, 빈 목록은 `[]` |
| 상세 API | `GET /api/v1/me/furniture-generations/{id}`, 200, 동일 DTO, UUID 형식 검증 |
| 응답 | 기존 13개 필드, null, 시각 직렬화, `Cache-Control: no-store`. 원본·검수 전 이미지 키와 실행 토큰은 비노출 |
| 권한 검사 | 활성 사용자 확인 후 본인 작업 조회. 없는·탈퇴 사용자는 `AUTH_INVALID_TOKEN`, 타인·없는 작업은 `FURNITURE_JOB_NOT_FOUND` |
| 목록 범위 | 본인 작업을 `createdAt DESC`로 최근 20개. 상태·만료 필터나 동률 정렬을 추가하지 않음 |
| 기능 비활성화 | 생성 기능을 꺼도 기존 목록과 상태를 조회할 수 있음 |
| 접수 응답 | 신규 접수는 업로드 처리 후 재조회. 중복 접수는 기존 예약 응답을 즉시 반환 |
| 트랜잭션과 잠금 | 조회는 비잠금 읽기. 접수·선점·생성권 정산·지급의 기존 잠금 순서와 원자성 유지 |

API 경로, DTO, 엔티티, 저장소 쿼리, DB migration, 워커 및 Lambda 실행 설정은 변경하지 않는다.

## 검증 기록

변경 전 원본 구현의 관련 테스트를 먼저 실행했다. 가구 생성과 생성권 정산 범위 15개 클래스, 88개 중 83개가 통과했고 실제 외부 환경이 필요한 기존 5개는 생략됐다. 실패와 오류는 없었다. 실행 시간은 42초다.

DB 계약 7개와 HTTP 계약 7개를 추가한 뒤, 원본 구현에서 같은 범위를 다시 검증했다. 16개 클래스, 102개 중 97개가 통과했고 기존 5개만 생략됐다. 이 결과를 확보한 후 조회를 옮겼다. 기존 테스트의 검증 내용과 fixture는 유지하고 조회 대상 빈만 바꿨다.

| 검증 | 변경 전 | 변경 후 |
| --- | --- | --- |
| 기존 관련 테스트 88개 | 83개 통과, 기존 환경 조건부 5개 생략 | 동일 83개 통과, 동일 5개 생략 |
| 조회 계약 검증을 보강한 102개 | 97개 통과, 기존 환경 조건부 5개 생략 | 동일 97개 통과, 동일 5개 생략 |
| 전체 모듈 컴파일과 테스트 | 이번 작업에서 변경 전 전체 실행은 하지 않음 | 컴파일 성공, 2,740개 통과, 기존 10개 생략 |
| 변경 diff와 별도 리뷰 | `git diff --check` 통과 | `git diff --check` 통과, 정합성·권한·트랜잭션 리뷰 PASS |

최종 `./gradlew compileJava test --console=plain`은 4분 39초에 종료 코드 0, `BUILD SUCCESSFUL`로 완료됐다. 모든 최종 집계에서 실패와 오류는 0개다. XML 391개를 합산했으며, 실행 항목의 중복을 보존해 관련 102개의 이름과 통과·생략 결과가 변경 전후에 같음을 확인했다. 기존 88개도 삭제나 이름 변경 없이 유지됐다.

| 최종 집계 | 전체 | 통과 | 생략 |
| --- | ---: | ---: | ---: |
| 사용자 API — 전체 재실행 | 2,125 | 2,118 | 7 |
| 가구 Lambda AI·Control·워커 — 재실행 | 9 | 9 | 0 |
| 나머지 모듈 — 입력이 같아 기존 결과 재사용 | 616 | 613 | 3 |
| 합계 | **2,750** | **2,740** | **10** |

54개 Gradle 태스크 중 14개를 실행했고 40개는 `UP-TO-DATE` 결과를 재사용했다. 관련 88개와 102개는 전체 결과에 포함되므로 중복 합산하지 않는다. 실행 시간은 빌드·테스트 소요 시간이며 API 성능 개선의 근거가 아니다. 테스트 건수도 커버리지 비율을 뜻하지 않는다.

생략 10개는 기존 환경 조건부 테스트다. 실제 가구 생성·개발 Lambda 5개, 실제 AI 사용자 모델 평가 2개, 실제 FastAPI 1개, 네이티브 libvips 2개이며, 이번 변경으로 비활성화한 테스트는 없다. 운영 배포 상태는 로컬 검증과 구분한다.

2026-10-19 커밋·병합 준비 시 원격을 갱신해 `origin/main`이 위 검증 기준 커밋과 같음을 확인했다. 최신 migration은 V93이며 이번 변경에는 migration 추가·수정이 없다. 최종 CI와 병합 상태는 해당 PR 이력으로 확인한다.

| 테스트 | 확인한 내용 |
| --- | --- |
| `FurnitureGenerationQueryIntegrationTest` — 신규 7개 | 생성 기능 비활성화 상태의 조회, 본인 최신 20개와 정렬, 빈 목록, nullable 응답, 만료·실패 작업, 타인·없는 작업과 탈퇴·없는 사용자 차단 |
| `FurnitureGenerationControllerTest` — 기존 3개 + 신규 7개 | 고정 JSON과 엄격 비교한 공개 13개 필드, null, 시간, 목록 순서와 빈 배열, `no-store`, UUID 400, 작업 조회 404, 인증 주체 없는 요청 401 |
| `FurnitureStabilityIntegrationTest` | 접수 용량 잠금으로 쓰기가 대기해도 일반 목록 조회가 계속 가능한지 확인 |
| 생성·정산 기존 통합 테스트 | 접수 직후 응답, 중복 접수, 생성 상태 전이, 검수, 생성권 예약·정산, 보관함 지급과 실패 시 롤백 |

HTTP 테스트는 실제 `CurrentUserArgumentResolver`와 테스트 인증 주체를 사용한다. 요청의 `userId`로 소유자를 바꿀 수 없는지도 확인한다. 보안 필터는 기존처럼 꺼 두므로 JWT 검증 필터 전체의 증거로 삼지 않는다. DB 테스트의 AI·스토리지는 mock이며 조회 중 호출되지 않았음을 검사한다. 실제 앱·운영 API·유료 AI 호출 검증과는 구분한다.

검증 명령은 Java 25와 Docker MySQL 8.4 Testcontainers 환경을 기준으로 한다.

```bash
# 변경 전 기존 테스트 및 조회 계약 검증
./gradlew :user-api:test \
  --tests 'com.triples.rougether.userapi.furniture.*' \
  --tests '*FurnitureBillingIntegrationTest' --console=plain

# 변경 후 전체 검증
./gradlew compileJava test --console=plain
git diff --check
```

## 계약 확인 범위

스펙 저장소의 원격 추적 main `a0d7545b2d83403ccbe770f8320a856c4611a1dc`에서 `domains/attendance/api.md`의 가구 생성 계약을 확인했다. 오래된 로컬 체크아웃 대신 원격 추적 main의 파일을 직접 읽었다.

스펙에 명시된 본인 작업 조회, 최근 20개, `items` 응답과 내부 이미지 키 비노출을 유지한다. 세부 nullable 필드, 캐시 헤더, 기능 비활성화 시 조회 가능 여부는 현재 구현을 회귀 기준으로 고정한다. 공통 시각 표기 정책은 미정이므로 이번 작업에서는 기존 직렬화를 유지한다.
