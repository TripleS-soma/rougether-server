# 어드민 메모리 전후 비교

운영과 분리된 로컬 MySQL에서 동일한 데이터와 JVM 옵션으로 변경 전후를 비교한다. 운영 접속, 배포, 강제 GC를 수행하는 도구가 아니다. JDBC 주소는 `127.0.0.1:13384/admin_memory`로 고정돼 있다. macOS의 `/usr/bin/time -l`을 사용한다.

## 비교 대상

- 리텐션 API 응답: 조회 기간 1, 35, 90일
- 활동 API 응답: 3개 날짜의 2개 페이지, 검색 및 기능 필터 3개
- 합계 12개 응답을 JSON 전체로 비교한다. 고정 KST Clock을 사용하므로 생성 시각까지 동일해야 한다. JVM 기본 시간대는 UTC로 고정하고 경계 fixture도 명시적인 UTC Instant로 저장한다.
- 고정 데이터: 사용자 500명, 사용자당 루틴 10개, 최근 30일 완료 기록. 봇, AI, 탈퇴 사용자는 각각 5명이며 기존과 동일하게 제외한다.
- 한 사용자에게 KST 자정 전후, 조회 시작/종료 시각의 경계 기록을 별도로 넣는다. 정상 통계와 경계 통계 모두 변경 전과 동일해야 한다.

## 실행

기존 컨테이너와 충돌하지 않는 전용 이름과 loopback 포트를 사용한다. 비밀번호 없는 root는 이 일회용 로컬 DB에서만 사용한다. 운영 데이터는 복사하지 않는다.

```bash
docker run -d --name rougether-admin-memory-20261114 \
  -p 127.0.0.1:13384:3306 --memory 1g \
  -e MYSQL_ALLOW_EMPTY_PASSWORD=yes -e MYSQL_DATABASE=admin_memory \
  mysql:8.4.11 --innodb-buffer-pool-size=256M --skip-log-bin

mkdir -p /tmp/rougether-admin-memory-baseline
git archive 4e9b24f1cb3cafd354c7a59db27c8ba6ece686f1 | tar -x -C /tmp/rougether-admin-memory-baseline

python3 qa/admin-memory/run.py \
  --baseline /tmp/rougether-admin-memory-baseline \
  --java-home /path/to/jdk-25/Contents/Home \
  --output /tmp/admin-memory-comparison --repetitions 3
```

전체 회귀 테스트 등 다른 JVM 부하를 종료한 뒤 실행한다. 기준판과 변경판은 순차 실행하고 먼저 실행하는 버전을 교대한다. JVM 옵션에 영향을 주는 `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, `JDK_JAVA_OPTIONS`와 운영 연결 환경 변수는 하위 프로세스에 전달하지 않는다.

## 측정값의 의미

| 값 | 의미 |
| --- | --- |
| `completionRows` | 실제 JPA 쿼리의 반환 행 수 |
| `retentionAllocatedBytesPerRequest` | 워밍업 후 리텐션 요청 스레드가 한 요청 동안 할당한 Java 바이트 수. 동시에 살아 있는 객체 크기가 아니다. |
| `activityColdAllocatedBytesPerRequest` | 캐시 miss를 포함한 활동 요청 전체의 Java 할당량 |
| `activityHitAllocatedBytesPerRequest` | 활동 캐시 hit 요청 스레드의 Java 할당량 |
| `cacheFillAllocatedBytes` | 8개 날짜의 캐시를 채울 때 요청 스레드가 할당한 양 |
| `cacheRetainedBytes` | 동일 캐시 8개를 유지한 상태와 해제한 상태의 로컬 full GC 후 전체 힙 차이를 8로 나눈 값. 캐시 유지 비용의 추정이며 순수 객체별 retained size 측정은 아니다. |
| `cacheAfterExpiryBytes` | TTL 경계 이후 다른 날짜를 읽은 상태와 캐시 해제 상태의 전체 힙 차이 |
| `maximumProcessRssBytes` | 시작부터 종료까지 로컬 JVM의 최대 RSS. 운영 컨테이너 working set 감소량이 아니다. |

캐시 경로를 두 차례 워밍업하고, 8개 복제본으로 신호를 키운다. 캐시 적재 힙 차이가 음수면 측정 실패로 처리하며, 만료 후 작은 힙 차이가 음수이면 해당 값은 null로 기록해 개선량으로 보고하지 않는다. 중앙값과 함께 각 항목의 유효 표본 수를 기록한다. 전체 프로세스 최대 RSS에는 이 8개 캐시 부하도 포함된다. 모든 샘플의 원본 수치와 중앙값은 `summary.json`에 저장한다. `responses.json`에는 개인정보가 없는 합성 데이터의 12개 전체 응답이 저장된다.

활동 캐시는 다음 조회 시 만료 항목을 정리한다. 요청이 없는 동안 즉시 제거하는 별도 타이머는 추가하지 않았으며, 최대 8개 날짜 제한은 유지한다. DTO 변환은 cache miss 때 추가 작업을 하므로 캐시 채우기 할당량과 hit 비용을 함께 비교한다.

MySQL의 `DISTINCT`와 날짜 계산 비용은 추가된다. 요청 시간도 함께 기록하며 JVM의 할당량 감소를 DB까지 포함한 전체 시스템 메모리 감소로 확대 해석하지 않는다. KST 변환은 운영 설정과 같은 UTC DB 세션을 전제로 한다.

검증이 끝나면 이번에 생성한 전용 컨테이너를 정리한다.

```bash
docker rm -f rougether-admin-memory-20261114
```
