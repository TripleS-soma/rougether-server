# 느린 AI 응답의 요청 스레드 점유 비교 (2026-09-22)

## 결론

이 로컬 실험에서는 외부 AI가 느려지면 동기 요청이 Tomcat 요청 스레드를 점유해 즉시 응답용 일반 경로까지 지연됐다. 같은 동기 호출을 FastAPI 경유로 바꾸는 것만으로는 개선되지 않았다. Spring MVC 비동기 반환과 비차단 HTTP 호출로도 일반 경로를 보호할 수 있었으므로, 이 현상만으로 별도 AI 서버가 필수라고 주장할 수 없다.

현재 FastAPI 기본 동시 실행 제한(2개)은 일반 경로를 보호했지만 AI 요청 40개 중 36개를 폴백 처리했다. 같은 제한을 Spring 안에 적용해도 비슷한 결과가 나왔으므로, 해당 효과는 서버 분리 자체보다 동시 호출 제한과 빠른 폴백에 귀속된다.

## 조건

- 코드 기준: PR #398의 `e9c50997`, 실제 `OpenAiCompatibleEmbeddingClient`와 `AiServiceClient` 사용. QA harness만 추가했다.
- Java 25.0.3, 실제 프로젝트 의존성의 Tomcat 11.0.22/Spring MVC. 제품 API/DB/인증을 기동하지 않은 별도 fixture다.
- Tomcat 플랫폼 요청 스레드 8개, Java CPU 인식 2개·heap 256MiB. 운영 스레드 수나 포화 임계점을 측정한 결과가 아니다.
- 모의 공급자는 정상 20ms 또는 지연 3초 후 동일 임베딩을 반환한다. 실제 Claude/OpenAI 장애나 응답 속도를 재현했다는 의미가 아니다.
- 각 조건 5초간 일반 경로 20 RPS(100건), AI 경로 8 RPS(40건), 요청별 개별 연결 풀 용량 여유 확보. 결과를 기다려 다음 요청을 보내지 않는 고정 도착률 방식이다.
- 전 경로 예열 후 5개 지연 조건을 정순/역순으로 2회 실행했다. 공급자 재시도는 0회다.
- JVM·FastAPI·모의 공급자·발생기는 한 로컬 호스트에서 실행했다. CPU 부하/OOM/프로세스 장애나 서버 간 물리적 격리 효과는 이 실험의 검증 범위가 아니다.

재현 명령과 설계는 [QA README](../../qa/ai-thread-isolation/README.md)에 있다.

## 결과

p95는 요청 예정 시점부터 응답까지의 지연이다. AI 없는 기준선은 일반 경로 p95 8.59ms, 정상 공급자(20ms)와 동기 직접 호출은 8.13ms였다.

| AI 3초 지연 조건 | 일반 경로 p95, 1차 / 2차 | AI 적용 / 폴백, 각 회차 | 관측 Tomcat busy 최대, 1차 / 2차 |
| --- | --- | --- | --- |
| 기존 동기 직접 호출 | 8,141.64 / 8,132.35ms | 40 / 0 | 8 / 8 |
| 동기 FastAPI 경유, 한도 16 | 8,167.69 / 8,155.87ms | 40 / 0 | 8 / 8 |
| 비동기 직접 호출 | 7.16 / 6.21ms | 40 / 0 | 1 / 0 |
| 동기 직접 호출 + 동시 2개 제한 | 6.24 / 7.01ms | 4 / 36 | 3 / 3 |
| 동기 FastAPI 경유, 기본 한도 2 | 6.81 / 7.64ms | 4 / 36 | 4 / 2 |

busy는 200ms 주기 표본의 최대값이며 절대 최대값이 아니다. 비동기에서 0으로 관측됐다는 것은 요청 처리를 전혀 하지 않았다는 뜻이 아니라 짧은 점유를 표본이 놓쳤다는 뜻이다.

전체 12개 조건에서 일반 요청은 예정/발사/HTTP 200 모두 1,200건, AI 요청은 예정/발사 모두 440건이었다. AI 적용 296건, 폴백 144건, 전송/HTTP 오류와 미발사 요청은 0건이다. 공급자 시작/완료 건수는 AI 적용 건수와 일치했다. HTTP 200 폴백을 AI 성공에 포함하지 않았다. 부하 발생기 발사 지연 최대는 6.79ms였다.

## 스레드 점유 근거

동기 직접 호출에서 busy 8개일 때의 요청 스레드는 `WAITING` 상태로 아래 호출 경로를 보였다.

```text
CompletableFuture.get
JdkClientHttpRequest.executeInternal
DefaultRestClient ... body
OpenAiCompatibleEmbeddingClient.callOnce
```

이는 스레드가 CPU를 계속 쓰고 있다는 의미가 아니다. 외부 응답을 기다리며 요청 풀의 자리를 차지하고 있어 다른 요청을 처리할 여유가 없어진 상태다. CPU 사용률만으로 진단하면 놓칠 수 있다.

비동기 조건은 `HttpClient.sendAsync` 결과를 `DeferredResult`로 반환한다. 관측된 요청 스레드는 대부분 Tomcat `TaskQueue.take`에서 다음 작업을 기다렸다. 반면 외부 공급자 동시 호출은 최대 25개로 늘었다. 비동기가 요청 스레드를 반환해도 진행 중인 외부 요청·연결·메모리·공급자 사용량이 없어지는 것은 아니다.

## 적용 판단

1. 현재 증상에 대한 직접적인 대안은 컨트롤러까지 이어지는 비동기 처리, 유한한 timeout, 동시 호출 제한과 폴백이다. `@Async`로 옮긴 다음 요청 스레드에서 `get`/`join`으로 기다리면 이 실험의 비동기 조건과 같지 않다.
2. AI 서비스 분리는 독립 배포, CPU/메모리/프로세스 격리, 별도 확장 또는 Python AI 도구 필요성으로 별도로 판단한다. 이 실험으로 FastAPI의 성능 우위나 분리의 필수성을 주장하지 않는다.
3. 가상 스레드는 이번에 측정하지 않았다. Java 25 환경의 다른 대안으로 검토할 수 있지만 외부 호출 한도와 장애 정책은 여전히 필요하다.
4. 제품 유사도 API의 비동기 전환은 아직 하지 않았다. DB 조회, 인증/보안 컨텍스트, async dispatch, deadline/취소, 사용자별 허용량, 실제 트래픽으로 추가 검증해야 한다. 주간 회고는 별도 batch 앱이므로 본 결과를 그대로 대입하지 않는다.

## 원자료

- [조건별 집계](../../qa/ai-thread-isolation/results/20260922/summary.json)
- [전체 요청 기록](../../qa/ai-thread-isolation/results/20260922/requests.csv)
- [최대 점유 표본의 요청 스레드 스택](../../qa/ai-thread-isolation/results/20260922/thread-snapshots.json)
- 상세 200ms 표본과 프로세스 로그: 로컬 `qa/ai-thread-isolation/build/run-20260922-231044/`

참고: [Spring MVC DeferredResult의 요청 스레드 반환](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html), [Spring Boot 가상 스레드 설정](https://docs.spring.io/spring-boot/reference/features/spring-application.html#features.spring-application.virtual-threads).
