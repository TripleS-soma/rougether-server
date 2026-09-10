# 투두 완료 UPDATE 통합: 최종 변경과 부하 검증

투두 완료에서 성장 보상 기록을 방 성장 처리보다 먼저 수행하도록 호출 순서를 바꿨다. 방 유무와 관계없이 투두 UPDATE가 2→1회로 줄고, SELECT·방 생성 보장·행 잠금·트랜잭션 경계는 그대로다. API 응답·일일 보상 한도·취소/재완료 규칙과 스키마는 바꾸지 않았다.

동일 조건의 유효 회차별 평균 업무 성공은 77,087→74,422건(-3.5%), 전체 p95 평균은 639.0→660.0ms(+3.3%)다. 조건별 두 번의 30초 로컬 실험으로 안정적인 개선율을 확정하지 않는다.

**확인한 성과는 동일한 응답·보상 정합성을 유지한 SQL 1회 감소다. 처리량 개선은 입증하지 못했고, 최종 비교의 네 회차 모두 `capacity_failed`로 3,000 RPS 목표에 미달했다.**

## 최종 코드와 실제 SQL

`TodoService.complete()`에서 `todo.recordGrowthReward(reward)`를 `roomGrowthService.award(userId, reward)`보다 먼저 실행한다. 방 생성 보장의 중간 flush가 완료 상태와 성장 보상을 함께 내보내므로, 이후 커밋에 성장 보상만 따로 UPDATE하지 않는다. 방 생성과 성장 지급이 실패하면 먼저 기록한 투두 값도 같은 트랜잭션에서 롤백된다.

| 보상 10 지급 요청 | 코드 | SELECT | INSERT 계열 | UPDATE 전체 | 투두 UPDATE | 업무 SQL 총수 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 기존 방 | 기존 | 6 | 2 | 4 | 2 | 12 |
| 기존 방 | 최종 | 6 | 2 | 3 | 1 | 11 |
| 방 없음 | 기존 | 6 | 2 | 4 | 2 | 12 |
| 방 없음 | 최종 | 6 | 2 | 3 | 1 | 11 |

INSERT 계열은 원장 INSERT와 방 upsert 각 1회다. upsert의 ON DUPLICATE KEY UPDATE를 UPDATE에 중복 집계하지 않는다. COMMIT·SET을 제외한 업무 SQL은 12→11회, 쓰기 SQL은 6→5회다. 이 감소율을 디스크 쓰기·지연·처리량의 같은 비율 개선으로 해석하지 않는다.

방이 있는 경우의 주요 순서는 다음과 같다.

```sql
-- 기존: 원장 INSERT 뒤 중간 flush
UPDATE user_wallets ...;
UPDATE todos SET status = ..., reward_amount = ...;
INSERT INTO personal_rooms ... ON DUPLICATE KEY UPDATE ...;
SELECT ... FROM personal_rooms ... FOR UPDATE;
UPDATE todos SET growth_reward_amount = ...;
UPDATE personal_rooms ...;

-- 최종: 중간 flush에서 투두 변경을 한 번에 반영
UPDATE user_wallets ...;
UPDATE todos SET status = ..., reward_amount = ..., growth_reward_amount = ...;
INSERT INTO personal_rooms ... ON DUPLICATE KEY UPDATE ...;
SELECT ... FROM personal_rooms ... FOR UPDATE;
UPDATE personal_rooms ...;
```

## 채택하지 않은 후보

먼저 방 존재 여부를 비잠금 SELECT로 확인하여 기존 방의 upsert를 건너뛰는 조합도 시험했다. 이 조합은 기존 방의 쓰기 SQL이 6→4회로 줄지만 SELECT가 1회 추가된다. 동일 조건 각 2회에서 평균 성공 75,602→71,387.5건(-5.6%), p95 674.5→734ms(+8.8%)여서 이득을 입증하지 못했다. 회차 편차가 있어 존재 확인이 성능 저하의 단독 원인이라고 확정하지 않는다.

추가 조회와 분기를 최종 코드에서 제거하고, UPDATE 통합만 별도 재검증했다. `RoomGrowthService`와 `PersonalRoomRepository`는 최종 변경에 포함되지 않는다. [미채택 후보의 전체 실험](2026-09-10-write-optimization.md)과 원본 JAR/SQL/수치는 보존했다.

## 검증

- 최종 코드에서 `./gradlew --no-daemon test :user-api:bootJar` 성공. 총 2,002건 중 2,001건 통과, 실패/오류 0, 기존 외부 AI live smoke 1건 제외. 변경 없는 Gradle 태스크는 up-to-date 결과를 재사용했다.
- 새 테스트는 기존 코드에서 방 유무 모두 UPDATE 2회여서 실패했고, 최종 코드에서 UPDATE 1회와 투두·지갑·원장·방 지급액 일치를 검증했다.
- 완료 결과를 실제 flush한 뒤 강제 실패시 기존 방 복원/새 방 생성 롤백, 투두 상태·보상 필드·지갑·원장 원자성(방 유무 두 경우).
- 서로 다른 사용자 8명의 첫 방 동시 생성, 같은 트랜잭션에서 신규 회원 저장과 첫 완료.
- 기존 회귀 테스트로 루틴·투두 혼합 동시 완료의 하루 50코인 상한, 부분 지급, 완료 취소·재완료, 타인 소유권, 중복 회수 방지, 첫 방 조회/배치 저장 경합, 레벨 5 모루 지급과 롤백을 확인했다.
- 실제 MySQL HTTP 응답 8개 전후 대조 통과. POST 완료 시각만 제외하고 HTTP status와 모든 나머지 필드가 같으며, 모든 GET 필드는 그대로 대조했다. 두 완료 사용자의 투두 보상·성장 보상·지갑·원장·방 포인트 10 일치.
- Python 부하 도구 테스트 56건 및 `git diff --check` 통과.

## 동일 조건 부하

실제 JWT, 읽기 80%(`/me`, `/today`) + 투두 완료 20%, 3,000 RPS × 30초(계획 90,000건), 사용자 20,000명, 오늘 투두 60,000건, 과거 무보상 완료 2,000,000건, 고정 VU 1,000. 매 회차 새 영속 MySQL 8.4 DB를 사용했다.

API 4 CPU/2 GiB/heap 1 GiB, DB 2 CPU/2 GiB/buffer pool 1 GiB, Hikari 20. Docker VM은 12 CPU/약 7.7 GiB이며 호스트와 자원을 공유한다. 두 버전 모두 앞선 조회 최적화와 동일한 후보 인덱스를 포함한다. 인덱스 전수 읽기와 읽기 100 RPS × 60초로 예열했고, flush-at-commit=1/sync-binlog=1/binlog ON을 유지했다.

| 회차 | 코드 | 업무 성공 | 미시작 | 전체 p95 | 완료 p95 | 평균 연결 대기 | 평균 연결 점유 | 요청 계수 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 기존 | 76,165 | 13,836 | 670.0ms | 659.4ms | 42.77ms | 5.70ms | 통과 |
| 2 | 최종 | 71,152 | 18,848 | 704.0ms | 696.0ms | 64.20ms | 6.56ms | 통과 |
| 3 | 최종 | 77,691 | 12,310 | 616.0ms | 601.0ms | 46.77ms | 5.75ms | 통과 |
| 4 | 기존 | 78,009 | 11,992 | 608.0ms | 595.0ms | 44.51ms | 5.49ms | 통과 |

회차별 평균 연결 획득 대기는 43.64→55.48ms, 연결 점유는 5.60→6.16ms다. Hikari acquire/usage의 부하 전후 TOTAL_TIME 차이÷COUNT 차이로 구했다. 모든 API의 연결 사용 한 번당 평균이며 투두 완료만의 점유 시간은 아니다. p95 평균은 회차별 p95의 산술 평균이다.

발송=업무 성공=서버 처리 완료 합계와 실행된 완료 요청의 사용자별 지갑·원장·방 성장 일치, 과거 200만 이력 불변을 확인했다. 미시작·지연 기준 때문에 용량 실패한 회차를 성공으로 바꾸지 않는다. 모든 판정과 측정 한계는 JSON에 포함한다.

기존→최종→최종→기존 순서로 계획했고, 발송+미시작이 계획 대비 ±1을 넘는 회차는 원본을 보존하되 평균에서 제외한다. 부하 fixture에는 루틴과 목표가 없고 모든 사용자 방이 준비되어 있다. 방 첫 생성·동시 취소·루틴·모루는 기능/SQL 검증 범위다. 짧은 예열은 모든 사용자/쓰기/JIT 예열을 보장하지 않는다. 발생기 CPU 경고도 원본 판정에 보존하며 프로세스 CPU 100%만으로 다중 코어 발생기 포화를 확정하지 않는다. AWS·다중 서버·장시간 운영 용량은 검증하지 않았다.

## 재현과 증거

- 기존 JAR SHA-256: `d8bf6a49d598a245f7a62ed3be765349215541a21a31f3be5b590310d3de8ff3`
- 최종 JAR SHA-256: `066e03420a8563d7bba9eeb59c50c73213cafd40fec176a9fcfcb60fe0239575`
- 측정 당시 작업공간 `output/worktrees/scale-engineering`, 브랜치 `codex/scale-engineering`, HEAD `966cf142e343d687698e03ecd1b8e5b4bf3c4a70` + 보관 diff. 측정 시점에는 이 변경의 커밋·머지·운영 배포를 수행하지 않았다.
- [전체 수치·판정·SQL·응답 대조·정합성·원본 hash](2026-09-10-todo-write-optimization.json)
- Git 제외 원본은 `qa/scale/results/write-flush-only-artifacts/`의 전후 JAR, `source-and-tests.json`, `write-change.patch`, `sql-comparison.json`, `cohort.json`, 테스트 로그와 실행 스크립트에 보관했다. JWT/seed 원본은 공유하지 않는다.
- SQL 진단과 두 후보 비교의 컨테이너·볼륨·네트워크는 회차별로 정리했다. 마지막 확인에서 이번 작업의 컨테이너와 볼륨은 없었고 기존 다른 프로젝트 리소스는 유지했다.

```bash
SCALE_API_CPUS=4 SCALE_DB_CPUS=2 SCALE_DB_POOL=20 \
SCALE_API_MEMORY=2g SCALE_API_HEAP=1024m SCALE_DB_MEMORY=2g \
SCALE_DB_BUFFER_BYTES=1073741824 python3 qa/scale/run-local.py mixed \
  --rate 3000 --duration 30 --users 20000 --todos 60000 --vus 1000 \
  --warmup 60 --history-per-user 100 --todo-indexes candidate \
  --warm-todo-indexes --skip-build --jar /absolute/path/to/baseline-or-final.jar
```
