# 로컬 고부하·정합성 실험

실제 JWT 검증과 MySQL commit을 포함하는 합성 부하 실험이다. 기존 `qa/k6` 사용자 여정 QA와 함께 사용한다. 목표 RPS와 측정한 성공 처리량을 구분하며, 운영 사용자 수나 운영 트래픽으로 표현하지 않는다.

실측 결과: [2026-09-10 기준선·CPU 비교 보고서](reports/2026-09-10-first-baseline.md).

## 실행

Docker Desktop, Java 25 Gradle toolchain, k6, Python 3.9 이상이 필요하다. 실행기는 새 compose project와 영속 MySQL 볼륨을 만들고, 종료 시 해당 project의 컨테이너·네트워크·볼륨을 정리한다. API는 loopback에만 공개하며 DB는 별도 내부 네트워크에 둔다. 외부 OAuth·AI·S3 호출은 부하 경로에 없다.

```bash
bash qa/scale/run-local.sh read --rate 100 --duration 30 --users 1000 --vus 200
bash qa/scale/run-local.sh mixed --rate 100 --duration 30 --users 1000 --vus 200
bash qa/scale/run-local.sh write --rate 100 --duration 30 --users 1000 --vus 200
bash qa/scale/run-local.sh contention --rate 100 --duration 30 --users 1000 --vus 200
```

각 회차는 독립된 DB를 사용한다. `--skip-build`는 이미 생성한 JAR를 재사용하며 JAR SHA-256을 manifest에 남긴다. `--todos`는 준비할 투두 수다. 기존 프로세스가 `--port`(기본 19080)를 사용 중이면 종료하지 않고 실행을 거부한다.

기본 자원은 API 2 CPU / 2GiB(힙 1GiB), MySQL 2 CPU / 2GiB(buffer pool 1GiB), Hikari 최대 10개 연결이다. `SCALE_API_CPUS`, `SCALE_API_MEMORY`, `SCALE_API_HEAP`, `SCALE_DB_CPUS`, `SCALE_DB_MEMORY`, `SCALE_DB_BUFFER_BYTES`, `SCALE_DB_POOL`로 비교 조건을 바꿀 수 있다. Docker VM 전체 할당량도 결과에 기록한다. 다른 앱과 호스트 자원을 공유하므로 이 결과만으로 독립 서버 용량을 확정하지 않는다.

## 부하와 판정

각 iteration은 HTTP 요청 한 개다. `constant-arrival-rate`와 고정 VU 풀을 사용하며 로그인·seed·warmup은 측정 구간 밖에서 실행한다. VU가 부족해 시작하지 못한 요청은 `dropped_iterations`로 남고 회차가 실패한다.

| 회차 | 요청 | 판정 목적 |
| --- | --- | --- |
| read | `/api/v1/me`, `/api/v1/today` | 인증된 읽기 응답과 지연 |
| mixed | 읽기 80%, 투두 완료 20% | 조회·DB 상태 변경의 혼합 부하 |
| write | 서로 다른 투두 완료 | 실제 완료 및 사용자별 지급·성장 정합성 |
| contention | 동일 투두의 중복 완료 | 중복 응답과 정확히 한 번 반영된 최종 상태 |

투두의 마감일은 seed 당시 KST 날짜다. 완료 코인은 현재 계약의 사용자별 일일 상한 50을 적용한다. 많은 투두를 같은 사용자로 완료하면 실제 지급액이 0인 성공 요청도 생긴다. 완료 요청 처리량과 코인이 지급된 요청 수·지급액을 함께 보고한다. 마감일 없는 투두나 잘못된 ID를 반복하여 쓰기 처리량을 만들지 않는다.

현재 seed는 사용자·지갑·개인 방·투두를 생성한다. 온보딩 목표·캐릭터, 루틴, 과거 완료 이력은 포함하지 않으므로 활동이 많은 실제 사용자의 전체 조회 비용을 대표하지 않는다. 해당 데이터를 채운 후속 회차와 구분한다.

정상 용량 판정은 실제 요청 수 ≥ 계획의 99%, 누락 0, 업무 성공률 ≥ 99.9%, 예상하지 않은 오류 ≤ 0.1%, API별 p95/p99 및 DB 검사 통과를 요구한다. 409 중복·429/503 거절은 업무 성공량에서 제외한다. 경합 회차는 정상 용량 성과로 분류하지 않는다. KST 자정을 넘긴 회차는 재실행한다.

## 결과와 관측 비용

결과는 `qa/scale/results/<run-id>-<scenario>/`에 저장된다.

- `manifest.json`: 원본 소스, JAR·실험 도구 hash, 자원·도구 버전·시각·종료 결과.
- `summary.json`, `console.txt`: k6 요청·성공·오류·지연·누락 집계.
- `db-audit.json`, `audit-snapshot.tsv`: 최종 업무 상태와 원장의 대조.
- `db-before.json`, `db-after.json`: DB 내구성 설정, 테이블 크기, SQL digest, 잠금·I/O 누계.
- `telemetry.jsonl`: k6 프로세스 CPU/RSS, 컨테이너 CPU·메모리·I/O, JVM·Hikari 관측.
- `api.jfr`, `gc.log`, `api.log`: JVM 프로파일과 GC·서버 로그.
- `verdict.json`: 통과·실패와 판정 근거. 자료가 없으면 통과로 간주하지 않는다.

manifest의 `server_arrivals`는 Actuator의 완료 요청 카운터 차이다. 서버 처리 수와 k6 요청 수를 대조하는 용도이며, 입구에서 관측한 도착 시각이나 처리 중인 요청 수를 뜻하지 않는다.

JFR profile과 주기적인 관리 API 호출도 자원을 사용한다. 같은 관측 조건에서 전후를 비교하고, 필요하면 관측 비용을 별도 회차로 측정한다. Docker와 발생기가 같은 맥북에서 동작하므로 발생기 병목을 서버 병목으로 단정하지 않는다.

`fixtures.json`의 JWT는 이 회차의 합성 사용자와 전용 키에 한정된다. 결과 폴더는 Git에서 제외한다. 포트폴리오에 공유할 때는 토큰·seed 원본을 제외하고 환경, 지표, 판정, 프로파일 분석을 추려 공개한다.

## 검증

```bash
python3 -m unittest discover -s qa/scale/tests -v
python3 -m unittest discover -s qa/scale/seed/tests -v
python3 -m py_compile qa/scale/*.py qa/scale/seed/*.py
k6 run qa/scale/scenarios/contract-self-check.js
git diff --check
```

작은 부하로 네 가지 회차와 DB 대조를 먼저 통과시킨다. 이후 100 → 1,000 → 3,000 → 10,000 RPS로 탐색하며 낮은 단계에서 기준을 넘으면 해당 지점의 프로파일부터 분석한다. 100,000 read / 30,000 mixed / 10,000 write RPS는 후속 도전 목표다. 현재 측정 성과는 회차별 증거로만 기재한다.

큰 fixture에서는 HTTP 없이 초기화만 검사할 수 있다. 아래 `<run-id>`를 준비한 회차로 바꿔 실행한다. 사용자 전체 검증은 `SharedArray` 초기화 안에서 한 번 수행하고, VU별 접근은 필요한 사용자 한 명으로 제한한다.

```bash
RATE=1 DURATION_SECONDS=30 PREALLOCATED_VUS=5000 MAX_VUS=5000 \
BASE_URL=http://127.0.0.1:19080 \
FIXTURE_PATH="$PWD/qa/scale/results/<run-id>/fixtures.json" \
SUMMARY_PATH=/tmp/scale-fixture-init-summary.json \
k6 run qa/scale/scenarios/fixture-init-self-check.js
```

참고: [k6 arrival-rate](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/), [발생기 자원과 VU](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/arrival-rate-vu-allocation/), [대규모 테스트의 발생기 관측](https://grafana.com/docs/k6/latest/testing-guides/running-large-tests/).
