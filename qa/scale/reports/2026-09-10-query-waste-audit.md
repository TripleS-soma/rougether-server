# 2026-09-10 조회 낭비 진단

앞서 혼합 부하를 걸었던 실제 서버 JAR에서 `/me`, `/today`, 투두 완료의 SQL 순서를 확인했다. 오늘 대상 루틴이 없어도 실행하는 완료 로그 조회, 요약 응답에 사용하지 않는 목표 상세 데이터, 자바에서 버리는 기간 외 루틴, 같은 투두의 두 번 UPDATE가 확인됐다. 애플리케이션 코드는 수정하지 않았다. 아래 개선안의 RPS·지연 효과는 아직 측정하지 않았다.

검사 대상은 `output/worktrees/scale-engineering`의 `966cf142e343d687698e03ecd1b8e5b4bf3c4a70`이며 애플리케이션 기반 커밋은 `fbd461546df2442e618c7b7bc86a488bd7ee0d9b`다. 다른 브랜치인 루트 체크아웃이나 운영 배포 상태의 감사 결과로 해석하지 않는다. 서버 JAR SHA-256은 `f397889a244376d67ee396922013ca49635b0582212f13551c4223d61c482dec`다.

**검증 조건과 결과**

기존 `qa/scale/compose.yml`의 MySQL 8.4, Java 25, API 4 CPU/2 GiB, DB 2 CPU/2 GiB, Hikari 20, 버퍼 풀 1 GiB를 사용했다. 사용자 3명·오늘 투두 9개를 넣고 실제 JWT 인증 HTTP 요청을 순차 실행했다. 사용자 한 명에는 목표 3개, 카테고리 1개, 활성 루틴 12개를 추가했다. 루틴 하나는 실제 완료 API로 완료했다. 요청마다 MySQL general log를 켜고 끄며 앱 DB 계정의 SQL만 수집했다. 이 로그는 쿼리 개수·컬럼·실행 순서의 증거이며, 로깅이 켜진 단건 요청의 시간은 성능 벤치마크에 사용하지 않는다.

| 요청/조건 | SELECT | INSERT 계열 | UPDATE | COMMIT |
| --- | ---: | ---: | ---: | ---: |
| `/me`, 사용자 당일 첫 접근 | 3 | 1 | 0 | 2 |
| `/me`, 반복 접근 | 3 | 0 | 0 | 1 |
| `/me`, 목표 3개 선택 | 3 | 0 | 0 | 1 |
| `/today`, 루틴 자체가 없음 | 4 | 0 | 0 | 1 |
| `/today`, 활성 12개 중 오늘 대상 2개 | 4 | 0 | 0 | 1 |
| `/today`, 활성 12개 중 오늘 대상 0개 | 4 | 0 | 0 | 1 |
| 투두 완료, 기존 방 있음 | 6 | 2 | 4 | 1 |
| 투두 완료, 방 없음 | 6 | 2 | 4 | 1 |

`INSERT ... ON DUPLICATE KEY UPDATE`는 INSERT 계열에 한 번만 셌다. SET 명령은 표에서 제외했으며 원본 JSON에는 포함했다. 첫 접근의 활동 기록 INSERT-SELECT는 별도 SELECT 명령으로 중복 집계하지 않았다. 성공 응답은 조회 200, 투두 완료 201이었다. 완료 사용자 2명 각각의 투두 보상·성장 보상·지갑·원장 합계·방 포인트가 모두 10이고 완료 투두가 1개임을 DB에서 확인했다. 동시성 검증이나 부하 재측정은 이 단건 진단 범위에 포함하지 않는다.

**1. `/today`의 사용하지 않는 완료 로그 SELECT — 우선 수정 후보**

`TodayService.java:47`에서 오늘 대상 루틴을 추린 뒤, `:55`에서 대상이 없어도 항상 완료 로그를 조회한다. 이 결과는 반환할 루틴의 완료 여부에만 쓰인다. 대상 루틴이 비어 있으면 빈 ID 집합을 사용하고 조회를 생략할 수 있다. 이 조건에서는 업무 SELECT를 현재 4회에서 3회로 줄일 수 있다.

진단에서 루틴 자체가 없는 사용자와, 활성 루틴을 모두 기간 밖으로 옮긴 사용자 모두 완료 로그 SELECT를 보냈다. 후자는 완료 로그가 실제로 남아 있어도 응답은 루틴 0개·완료 건수 0이었다. 앞선 투두 중심 혼합 부하 데이터에도 루틴이 없으므로, 이 생략 조건은 그 부하에 직접 적용된다. SELECT 25% 감소가 HTTP 지연 25% 개선을 뜻하지는 않는다.

**2. `/me`의 온보딩 요약이 목표 상세 목록까지 생성**

`OnboardingQueryService.java:41`의 `getSummary()`는 전체 온보딩 응답을 만드는 `compute()`를 호출한다. `:48`에서 목표를 join fetch 하고 `:50`에서 ID·코드·이름이 담긴 `GoalSelection` 목록을 만든 뒤, 요약에서는 완료 여부·대표 목표 ID·선택 캐릭터 ID만 반환한다.

실제 목표 SQL은 10개 컬럼을 조회하고 목표 정렬까지 수행했다. 목표 3개가 있는 요청에서도 최종 요약에 목표 이름·코드·목록은 없었다. 요약 전용 projection을 만들면 사용하지 않는 컬럼 조회와 DTO 생성을 줄일 수 있다. 선택 캐릭터도 전체 보유 엔티티 대신 필요한 ID만 조회하는 후보가 된다. 쿼리 수가 실제로 줄어드는지는 구현한 SQL 형태에 따라 다시 확인해야 한다.

`completed`는 ‘목표가 하나라도 존재하고 선택 캐릭터가 있음’이라는 현재 조건을 유지해야 한다. 대표 목표 존재 여부로 바꾸면 의미가 달라진다. 기존의 정렬 및 대표 목표 선택 규칙도 보존한다.

**3. `/today`가 DB에서 더 읽은 뒤 자바에서 버림**

`TodayService.java:47–52`는 삭제되지 않은 ACTIVE 루틴 전체를 로딩하고 기간·반복 규칙을 자바에서 검사한다. 진단 데이터의 활성 12개 중 5개는 기간 종료, 5개는 미래 시작, 2개만 오늘 대상이었다. SQL에는 시작일·종료일 조건이 없어 12개 모두 로딩되며 응답에는 2개만 남았다.

시작일·종료일의 nullable/inclusive 의미를 유지한 SQL 조건을 먼저 적용하면 명백한 기간 외 행을 DB에서 제외할 수 있다. 격주·매월·매년 등 반복 규칙은 계약을 확인하며 기존 판정을 보존한다. 앞선 혼합 부하에는 루틴이 없어서 이 개선의 효과를 검증할 수 없으며, 루틴 데이터가 있는 별도 부하 조건이 필요하다.

컬럼도 과하게 조회한다. 완료 여부에는 routine ID만 필요한데 `RoutineLogRepository.java:54`는 로그 9개 컬럼을 읽는다. `/today` 투두 응답 조립에는 ID·category ID·title·due date·due time·status·completed at의 7개 값이 필요한데 `TodoRepository.java:55`의 엔티티 조회는 description TEXT와 외부 연동 값 등을 포함한 17개 컬럼을 읽는다. 오늘 화면 전용 ID/DTO projection을 적용할 후보이며, 소유권·삭제·날짜 필터와 정렬은 그대로 유지한다. 컬럼 수의 비율을 전송 바이트나 성능 개선율로 해석하지 않는다.

**4. 투두 완료 한 번에 같은 투두 UPDATE 두 번 — 쓰기 경로의 추가 발견**

`TodoService.java:154–161`은 완료 상태와 보상을 설정하고 방 성장 처리 뒤 투두의 성장 보상을 기록한다. 중간에 `RoomGrowthService.java:43`이 호출하는 `PersonalRoomRepository.java:16`의 `flushAutomatically=true`가 변경을 DB로 내보낸다. 실제 기존 방 요청에서 다음 순서를 확인했다.

```sql
INSERT INTO wallet_histories ...;
UPDATE user_wallets SET balance = 10, ...;
UPDATE todos SET completed_at = ..., reward_amount = 10,
                 reward_currency_type = 'COIN', status = 'COMPLETED', ...;
INSERT INTO personal_rooms ... ON DUPLICATE KEY UPDATE ...;
SELECT ... FROM personal_rooms ... FOR UPDATE;
UPDATE todos SET growth_reward_amount = 10, ...;
UPDATE personal_rooms SET growth_points = 10, ...;
COMMIT;
```

방이 이미 있어도 생성 보장 upsert를 실행하며, 같은 투두는 서로 다른 필드 때문에 두 번 UPDATE된다. 방 유무에 따른 생성 경로와 투두 변경 시점을 조정해 중복 쓰기를 줄이는 실험을 할 수 있다. 다만 신규 회원 flush와 방이 없는 기존 사용자 지원 때문에 `ensureExists()`나 flush 설정을 단순 삭제해서는 안 된다. 사용자→지갑→방 잠금 순서, 일일 보상 상한, 원장·성장 원자성, 동시 완료/취소를 유지하는 검증이 필요하다.

**낭비로 판단하지 않은 부분**

카테고리가 있는 루틴·투두와 완료 로그를 넣은 `/today`도 SELECT 4회였고 카테고리를 별도 조회하지 않았다. 이번 재현에서 N+1은 확인되지 않았다. ID 접근만 보고 fetch join을 추가할 근거는 없다. 일일 활동 기록은 당일 첫 접근에 INSERT-SELECT 1회, 반복 요청에는 없었다. 투두 완료의 사용자·지갑·방 잠금과 루틴/투두 각각의 보상 합계 조회는 현재 정합성 규칙에 필요하므로 불필요한 조회로 분류하지 않았다.

권장 순서는 빈 루틴의 로그 조회 생략 → `/me` 요약 전용 조회 → `/today` projection·기간 조건 → 완료 경로의 중간 flush/생성 보장 정리다. 각각 같은 조건으로 전후 부하를 비교해 SQL 횟수, DB 연결 점유 시간, 성공 처리량, p95와 보상 정합성을 함께 확인한다. QueryDSL 도입 없이 기존 Spring Data JPA의 projection/JPQL로 시작할 수 있다.

성공 진단 실행 ID는 `20260910T132713-4084d4-query-probe`다. [요청별 SQL·응답·정합성 결과](2026-09-10-query-waste-audit.json)에 전체 증거를 보관했다. 최초 진단은 로그의 binary JSON 표현을 숫자 집계에서 처리하지 못했고 정합성 확인 SQL의 테이블명도 잘못되어 제외했다. 수정 후 새 DB에서 위 8개 요청을 다시 실행했다. 두 진단 환경의 컨테이너·볼륨·네트워크를 종료·삭제했으며, 마지막 확인에는 기존의 다른 프로젝트 MySQL만 남아 있었다. 애플리케이션 Java/Gradle 변경은 없으며 `git diff --check`를 통과했다.
