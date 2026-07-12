# Rougether 서비스 품질 기준선

> 상태: v0 초안
>
> 기준일: 2026-07-12 KST
>
> 기준 코드: `a44e9cad6b0b9fe05c9c977d0e9235010f9a42bf`

이 문서는 Rougether 백엔드의 품질을 "테스트가 많다" 같은 설명이 아니라 반복해서 측정할 수 있는 숫자로 관리하기 위한 출발점이다. 현재 측정 가능한 값, 아직 측정할 수 없는 값, 팀이 합의할 초기 목표를 구분한다.

## 판정 규칙

- **측정됨**: 저장소의 명령, CI 결과 또는 운영 지표로 같은 값을 다시 계산할 수 있다.
- **대리지표**: 실제 서비스 품질을 직접 재지는 않지만 현재 위험을 줄이는 보호장치가 있다.
- **미측정**: 기능이나 테스트가 일부 있어도 해당 품질을 수치로 수집하지 않는다.
- `v0 목표`는 팀 논의를 위한 초기값이다. 수집 장치와 합의 없이 branch protection이나 운영 알람 기준으로 간주하지 않는다.
- 실제 측정값이 없는 항목을 정상 또는 달성으로 기록하지 않는다.

## 현재 기준선

| 항목 | 2026-07-12 측정값 | 상태 | 재현 근거 |
| --- | ---: | --- | --- |
| Java 테스트 소스 | 99개 | 측정됨 | 각 모듈의 `src/test/**/*.java` |
| JUnit test suite | 100개 | 측정됨 | Gradle XML test result |
| JUnit test case | 551개, 실패 0, 오류 0, skip 0 | 측정됨 | `./gradlew clean test` |
| 이름 기반 통합 테스트 | 25개 | 대리지표 | `*IntegrationTest.java`, `*FlowIntegrationTest.java` |
| 이름 기반 rollback 테스트 | 5개 | 대리지표 | `*RollbackTest.java` |
| 이름 기반 transaction 테스트 | 3개 | 대리지표 | `*TransactionTest.java` |
| Flyway migration | V1-V14, 14개 | 측정됨 | `domain/src/main/resources/db/migration` |
| 테스트 커버리지 | 값 없음 | 미측정 | JaCoCo/Sonar 설정 없음 |
| 운영 API 지연시간, 오류율, 처리량 | 값 없음 | 미측정 | `health`, `info` 외 운영 metric 미수집 |
| 로컬 핵심 QA 부하 | 1,527 journey, 16,807 request, 실패 0, p95 15.47ms | 측정됨(로컬) | [2026-07-12 k6 기준선](load-test-baseline-2026-07-12.md) |
| 동시 요청 정합성 | 값 없음 | 미측정 | 경쟁 트랜잭션을 동시에 실행하는 테스트 없음 |
| read/write DB 복제 지연 | 적용 대상 없음 | 미측정 | 단일 RDS, read replica 및 routing datasource 없음 |

`./gradlew clean test`는 Docker Desktop 29.0.1과 MySQL 8.4 Testcontainers를 사용한 로컬 실행에서 35초가 걸렸다. 이 시간은 장비와 Gradle cache에 영향을 받으므로 성능 목표가 아니라 실행 환경 확인용 참고값이다. `user-api` 통합 테스트는 MySQL 8.4를 사용하고 `admin-api` 기본 테스트는 MySQL 호환 모드 H2를 사용한다.

## 현재 강제되는 품질 게이트

PR이 `main`을 대상으로 열리면 [PR Gate](../../.github/workflows/pr-gate.yml)가 다음 항목을 강제한다.

1. `./gradlew test` 성공
2. `user-api` Linux/amd64 Docker image build 성공
3. `admin-api` Linux/amd64 Docker image build 성공

`main` 배포 시 [Docker ECR Deploy](../../.github/workflows/docker-publish.yml)는 테스트와 image build를 다시 수행하고, commit SHA image를 EC2에 배포한 뒤 내부 및 외부 health endpoint를 확인한다. 새 image의 내부 health check가 실패하면 직전 image로 rollback한다.

다음 항목은 아직 강제 게이트가 아니다.

- JaCoCo coverage threshold
- SonarQube/SonarCloud quality gate
- 저장소가 직접 관리하는 정적 분석 설정
- k6 등의 부하 및 시나리오 테스트
- 실제 경쟁 트랜잭션 기반 동시성 테스트
- 운영 availability, latency, error-rate alert

## v0 SLI/SLO 제안

| ID | 품질 지표와 계산 방법 | v0 목표 | 현재 상태 |
| --- | --- | ---: | --- |
| `Q-CI-01` | 필수 PR check 성공 수 / 실행된 필수 PR check 수 | merge 전 100% | CI에서 강제 |
| `Q-TEST-01` | 성공 test case 수 / 전체 test case 수 | merge 전 100% | 551/551 |
| `Q-CODE-01` | 새 blocker/critical 정적 분석 finding 수 | 0개 | 미측정 |
| `Q-CODE-02` | 전체 line/branch coverage, 변경 code line coverage | line 70%, branch 60%, 변경 code 80% 이상 | 미측정 |
| `Q-API-01` | 성공한 health probe 수 / 전체 health probe 수, 월 단위 | 99.5% 이상 | 미측정 |
| `Q-API-02` | HTTP 5xx 응답 수 / 전체 API 응답 수, 일 단위 | 1.0% 미만 | 로컬 k6 0%, 운영 미측정 |
| `Q-API-03` | upload/AI 제외 일반 API server latency | p95 500ms 이하, p99 1초 이하 | 로컬 k6 p95 15.47ms/p99 20.90ms, 운영 미측정 |
| `Q-DATA-01` | 중복 보상, 중복 구매, 중복 claim 등 치명적 정합성 위반 | release당 0건 | 자동 집계 없음 |
| `Q-PUSH-01` | 유효 token 대상 FCM 요청 성공 수 / 전체 FCM 요청 수 | 95% 이상 | 상태는 저장하지만 집계 없음 |
| `Q-PUSH-02` | 알림 생성부터 FCM 요청까지의 시간 | p95 60초 이하 | 미측정 |
| `Q-DEPLOY-01` | 배포 후 내부/외부 health check 성공 수 / 전체 배포 수 | 100% | 배포 workflow에서 강제 |

Availability와 latency 목표는 운영 관측 도구가 붙은 뒤 첫 2주 실측값으로 재검토한다. 트래픽 특성이 다른 asset upload와 향후 AI API는 별도 SLI를 정의한다.

## 핵심 도메인 품질 지도

| 품질 위험 | 현재 보호장치 | 아직 증명하지 못한 것 |
| --- | --- | --- |
| 루틴/투두 보상 중복 지급과 일일 상한 | MySQL 통합 테스트, rollback 테스트, 지갑 row 비관적 lock | 같은 지갑에 실제 요청 여러 개가 동시에 진입하는 경쟁 상황 |
| 집 가입 정원과 미션 보상 claim | 집/미션 flow 테스트, 집과 미션 row 비관적 lock | 정원 마지막 자리 동시 가입, 마지막 progress 동시 기여, 동시 claim |
| 소유권 guard | controller/service 테스트에서 타 사용자 접근 차단 사례 검증 | 로그인부터 API까지 주요 사용자 시나리오 전체를 묶은 회귀 테스트 |
| Flyway 안전성 | MySQL 8.4 빈 DB에 V1부터 최신 migration 적용 | 운영과 같은 과거 snapshot upgrade, 중복 version 자동 검사 |
| 알림과 FCM | transaction commit 후 event 발행, 비동기 executor, push status 저장 | process crash 시 유실, retry/backoff, DLQ, device 수신 성공 |
| 분 단위 routine reminder | 단일 instance scheduler와 중복 발송 조회 | 여러 app instance가 같은 분에 실행될 때의 분산 중복 방지 |
| DB read 확장 | 단일 datasource로 read-after-write 일관성 유지 | replica lag, read routing, stale read 허용 범위 |

비관적 lock은 동시성 방어 구현이 있다는 증거이지 동시성 품질이 검증됐다는 뜻은 아니다. 별도 thread와 별도 transaction을 barrier로 동시에 시작해 최종 DB 상태를 확인해야 `Q-DATA-01`의 자동 검증으로 인정한다.

## 현재 운영 구조가 만드는 한계

- app은 단일 EC2에서 `user-api`, `admin-api` Docker container로 실행된다.
- DB는 단일 RDS MySQL이며 Terraform에서 `multi_az = false`다.
- read replica와 read/write datasource routing은 없다.
- Actuator는 `health`, `info`만 노출한다. latency, throughput, error rate를 집계하지 않는다.
- FCM은 `AFTER_COMMIT` event와 process 내부 thread pool(core 2, max 4, queue 100)로 처리한다. durable message queue가 아니다.
- routine reminder는 매분 실행되며 분산 scheduler lock이 없다. app을 여러 instance로 늘리기 전에 중복 실행 방어가 필요하다.

이 구조는 초기 개발 환경에는 단순하고 적합하지만, 현재 상태로는 multi-instance 안정성이나 장애 복구 목표를 달성했다고 판단하지 않는다.

## 측정 도입 순서

1. **코드 품질 수치화**: JaCoCo XML/HTML report와 CI artifact를 추가하고 `Q-CODE-02`의 실제 baseline을 기록한다. 정적 분석 도구를 선택한 뒤 새 blocker/critical finding 0개를 gate로 만든다.
2. **운영 관측성**: Micrometer 기반 HTTP latency, request count, 5xx, JVM, HikariCP 지표를 수집하고 `Q-API-*` dashboard와 alert를 만든다.
3. **QA 시나리오 부하 테스트**: routine/todo lifecycle의 로컬 기준선은 측정했다. 다음으로 집 가입, 미션 기여/claim을 추가하고 QA 환경에서 동시 사용자 수, throughput, p95/p99, 오류율을 기록한다.
4. **동시성 회귀 테스트**: MySQL Testcontainers에서 별도 transaction을 동시에 시작해 지갑, 집 정원, 미션 claim의 최종 상태를 검증한다.
5. **분산 실행 준비**: multi-instance 전 scheduler lock을 추가하고, 알림은 outbox와 durable queue(SQS 등) 도입 여부를 결정한다.
6. **DB read 확장 검증**: read replica를 실제 도입할 때만 routing 정책, 허용 replication lag, read-after-write 우회 규칙과 장애 전환 테스트를 추가한다.

## 재현 및 갱신 규칙

로컬 전체 검증에는 실행 중인 Docker daemon이 필요하다.

```bash
docker info
./gradlew clean test
git diff --check
```

기준선을 갱신하는 PR은 다음 정보를 함께 변경한다.

1. 측정 날짜와 기준 commit SHA
2. 실행한 명령과 환경
3. 이전 값과 새 값
4. 실패, skip, 제외 범위
5. 목표를 바꿨다면 변경 이유

테스트 파일 수나 test case 수는 보조 지표다. 테스트 수가 늘어도 핵심 위험을 검증하지 않으면 품질이 향상됐다고 판단하지 않는다.
