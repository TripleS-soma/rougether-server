# k6 핵심 QA 부하 테스트

이 디렉터리는 Rougether의 핵심 사용자 흐름을 HTTP 수준에서 반복해 오류율과 지연시간을 측정한다. 기본 실행은 외부 환경이 아니라 로컬의 임시 MySQL 8.4와 `user-api`를 사용한다.

## 검증하는 흐름

각 virtual user는 개발용 로그인으로 전용 사용자를 만든 뒤 다음 흐름을 반복한다.

1. 내 회원 정보 조회
2. 루틴 생성, 당일 완료, 단건 조회
3. 투두 생성, 당일 완료
4. 오늘 현황에서 완료 항목 반영 확인
5. 투두와 루틴 완료 취소 후 생성 데이터 삭제

기본 모드에서는 VU가 처음 실행될 때 각자 개발용 사용자를 만들고 access token을 VU 메모리에만 보관한다. token은 k6 `setup_data`, summary, console, app log에 기록하지 않는다.

## 실행

필수 도구는 Docker Desktop, `curl`, k6다. macOS에서는 `brew install k6`로 설치할 수 있다.

```bash
./qa/k6/run-local.sh smoke
./qa/k6/run-local.sh baseline
./qa/k6/run-local.sh stress
```

| profile | 부하 형태 | 용도 |
| --- | --- | --- |
| `smoke` | 1 VU, 1 iteration | API 계약과 실행 환경 확인 |
| `baseline` | 15초 ramp-up, 최대 10 VU, 총 75초 | 개발 장비 기준 반복 측정 |
| `stress` | 최대 50 VU, 총 120초 | 병목과 실패 시작점 탐색 |

`run-local.sh`는 다음 자원을 자동으로 관리한다.

- host port `13306`의 tmpfs MySQL 8.4 container
- host port `18080`의 `user-api`
- 종료 시 app process와 MySQL container 정리
- `qa/k6/results/<UTC timestamp>-<profile>/`에 k6 summary와 app log 저장

포트와 think time은 환경변수로 바꿀 수 있다.

```bash
K6_API_PORT=28080 \
K6_DB_PORT=23306 \
K6_THINK_TIME_SECONDS=0.5 \
./qa/k6/run-local.sh baseline
```

## 통과 기준

`smoke`와 `baseline`은 [서비스 품질 기준선](../../docs/quality/service-quality-baseline.md)의 v0 목표를 사용한다.

- HTTP 실패율 1% 미만
- check 성공률 99% 초과
- 전체 journey 성공률 99% 초과
- 정상 응답 p95 500ms 미만, p99 1초 미만

`stress`는 같은 threshold를 적용하지만 통과 자체보다 어느 VU 구간에서 latency와 오류가 증가하는지 찾는 데 목적이 있다.

## 안전장치

`core-journey.js`는 기본적으로 `localhost`와 `127.0.0.1`만 허용한다. 원격 환경은 `ALLOW_REMOTE=true`와 별도 test account의 `ACCESS_TOKENS`를 모두 제공해야 하며, 원격에서는 `/auth/dev-login`을 호출하지 않는다. token 값은 VU 메모리에서만 사용하고 결과 파일이나 log에 출력하지 않는다.

운영 환경에는 이 시나리오를 실행하지 않는다. 원격 QA 환경에서 실행할 때도 데이터 생성과 재화 변경을 허용하는 전용 계정 및 별도 DB를 사용한다.

## 해석 범위

k6 결과는 HTTP 부하에서의 latency, error rate, throughput을 보여준다. 이것만으로 중복 보상이나 정원 초과가 없음을 증명하지는 않는다. 데이터 정합성은 별도 transaction과 barrier를 사용하는 MySQL 동시성 테스트에서 최종 DB 상태를 검증해야 한다.
