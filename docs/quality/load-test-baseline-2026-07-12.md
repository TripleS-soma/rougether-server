# k6 핵심 QA 로컬 기준선 - 2026-07-12

이 문서는 [k6 핵심 QA 부하 테스트](../../qa/k6/README.md)의 `baseline` profile을 로컬에서 실행한 최초 측정 결과다. 운영 capacity가 아니라 시나리오 정상 동작과 이후 비교를 위한 개발 장비 기준선이다.

## 실행 조건

| 항목 | 값 |
| --- | --- |
| 실행 시각 | 2026-07-12 16:06 KST |
| 기준 backend | `a44e9cad6b0b9fe05c9c977d0e9235010f9a42bf` 이후 이 PR의 k6 시나리오 |
| 명령 | `./qa/k6/run-local.sh baseline` |
| 부하 발생기 | k6 1.4.2, darwin/arm64 |
| 장비 | MacBook Pro, Apple M4 Pro 12 core, 48GB memory |
| app | host에서 실행한 `user-api`, MySQL profile, port 18080 |
| DB | Docker MySQL 8.4, tmpfs, port 13306 |
| network | 같은 장비의 loopback |
| profile | 15초 0→5 VU, 45초 5→10 VU, 15초 10→0 VU |
| think time | iteration당 0.2초 |

각 VU는 자신만의 test user로 루틴과 투두를 생성하고 완료한 뒤 오늘 현황을 확인하고, 완료 취소와 데이터 삭제까지 수행했다.

## 결과

| 지표 | 측정값 | v0 목표 | 판정 |
| --- | ---: | ---: | --- |
| 완료 journey | 1,527개 | 참고값 | 측정 |
| 중단 journey | 0개 | 0개 | 통과 |
| HTTP request | 16,807개 | 참고값 | 측정 |
| 평균 request rate | 223.75 req/s | 참고값 | 측정 |
| check 성공 | 21,388 / 21,388 | 99% 초과 | 통과 |
| journey 성공률 | 100% | 99% 초과 | 통과 |
| HTTP 실패율 | 0% | 1% 미만 | 통과 |
| HTTP latency 평균 | 7.20ms | 참고값 | 측정 |
| HTTP latency p95 | 15.47ms | 500ms 미만 | 통과 |
| HTTP latency p99 | 20.90ms | 1초 미만 | 통과 |
| HTTP latency max | 131.87ms | 참고값 | 측정 |
| journey duration p95 | 101ms | 참고값 | 측정 |
| journey duration p99 | 115.74ms | 참고값 | 측정 |

모든 k6 threshold가 통과했다. 실행 후 summary의 `setup_data`는 `null`이었고, summary, console, app log에서 인증 token pattern이 검출되지 않았다. app process와 임시 MySQL은 스크립트 종료 시 정리됐다.

## 해석 제한

- loopback network와 tmpfs DB 결과이므로 EC2, RDS, TLS, public network, load balancer 비용이 포함되지 않는다.
- 최대 10 VU의 짧은 baseline이며 서비스의 최대 처리량이나 saturation point를 측정한 stress test가 아니다.
- VU마다 다른 사용자를 사용하므로 같은 지갑 row에 대한 집중 lock contention은 만들지 않았다.
- HTTP 응답과 사용자 흐름은 검증했지만 최종 DB 정합성을 직접 조회하지 않는다.
- 한 번의 실행 결과다. 성능 회귀 판정에는 같은 환경에서 최소 3회 실행한 중앙값이 필요하다.

따라서 이 결과로 “동시 사용자 10명까지 운영에서 안전하다”거나 “223 req/s를 보장한다”고 결론 내리지 않는다. 현재 결론은 로컬 기준선에서 핵심 routine/todo lifecycle이 오류 없이 실행됐고, 이후 동일 조건 회귀 비교가 가능해졌다는 것이다.

## 다음 측정

1. 같은 profile을 3회 실행해 request rate와 p95/p99 중앙값 기록
2. 집 가입, 미션 기여, claim 시나리오 추가
3. 별도 transaction 동시성 테스트로 지갑·정원·claim 최종 DB 상태 검증
4. QA EC2/RDS 환경에서 network와 실제 DB I/O를 포함한 baseline 실행
5. `stress` profile로 latency 급증과 오류 발생 지점 탐색
