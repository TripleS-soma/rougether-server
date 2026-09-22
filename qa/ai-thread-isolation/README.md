# 느린 AI 응답과 일반 API 스레드 점유 비교

외부 AI 응답 지연이 공유 Tomcat 요청 스레드를 점유하는지, 원격 FastAPI 경유·비동기 HTTP·동시 호출 제한 중 무엇이 일반 요청을 보호하는지 비교하는 로컬 실험이다. 실제 공급자 장애 사실이나 운영 처리량을 검증하지 않는다. 실험용 HTTP 경로는 제품 JAR에 포함되지 않는다.

## 실행

프로젝트의 Java 25와 `ai-service` 잠금 Python 의존성을 사용한다.

```bash
uv sync --directory ai-service --frozen
./gradlew -I qa/ai-thread-isolation/probe.gradle :user-api:prepareAiIsolation
ai-service/.venv/bin/python qa/ai-thread-isolation/run.py
```

결과는 `qa/ai-thread-isolation/build/run-*/`에 저장한다. `summary.json`은 각 조건의 집계, 개별 JSON은 요청별 기록·스레드 점유 표본·최대 점유 시 스택이다. Java/FastAPI/모의 공급자는 모두 임시 loopback 포트를 쓰고 성공·실패 시 종료한다. 실제 AI 키·AWS·DB 연결은 사용하지 않는다.

## 고정 조건

- 같은 JVM, Tomcat 11/Spring MVC 라이브러리를 사용한다. 제품의 `OpenAiCompatibleEmbeddingClient`, `AiServiceClient`를 그대로 호출한다.
- 제품 Controller/인증/DB 없이 `/normal` 즉시 응답과 `/ai/{mode}` 경로만 등록한다. 일반 API 결과는 네트워크·컨테이너 스레드 기전만 의미하며 실제 루틴/집 API 전체 부하는 아니다.
- Tomcat 플랫폼 요청 스레드 8개, 최대 연결/accept backlog 각각 256. Java CPU 인식 2개, heap 256MiB. 운영 스레드 설정/포화 임계점은 측정하지 않는다.
- 한 로컬 호스트에서 JVM, FastAPI 2개, 모의 공급자, 부하 발생기를 실행한다. 프로세스/호스트 장애·CPU/메모리 격리 효과는 비교하지 않는다.
- 조건별 5초 동안 일반 요청 20건/초(100건), AI 요청 8건/초(40건). 두 종류의 HTTP 연결 풀을 분리하고 요청 완료를 기다려 다음 요청을 발사하지 않는다.
- 모든 경로를 공급자 20ms로 예열한다. AI 없는 기준선, 20ms 정상 공급자 조건을 먼저 확인한 뒤 3초 지연을 주입한다.
- 5개 지연 조건을 정순/역순으로 2회 측정한다. 결과는 시작한 요청이 모두 완료된 뒤 집계한다. 정상 수신만 골라 p95를 계산하지 않으며 발생 예정 시점부터 응답까지의 지연에 부하 발생기 지연도 포함한다.
- 재시도는 꺼서 호출 수를 일치시킨다. 이는 응답 지연 기전 비교이며 장기 무응답·공급자 429/5xx·재시도 폭증 시험은 아니다.

## 비교 조건

| 이름 | 동작 |
| --- | --- |
| `sync-direct` | 기존 동기 임베딩 클라이언트로 공급자 직접 호출 |
| `sync-remote-wide` | 현재 동기 원격 어댑터→실제 FastAPI. FastAPI 한도를 16으로 두어 Tomcat 8개보다 먼저 제한하지 않음 |
| `async-direct` | 실험용 JDK `HttpClient.sendAsync` + Spring MVC `DeferredResult`. 기다리는 동안 요청 스레드를 반환 |
| `sync-direct-bounded` | 직접 동기 호출 앞에 동시 2개 `Semaphore.tryAcquire`만 추가. 대기열 없이 초과 요청은 폴백 |
| `sync-remote-two` | 현재 FastAPI 기본 임베딩 동시 실행 2개. 초과 요청은 기존 Java 예외 경로를 통해 폴백 |

`sync-direct`/`sync-remote-wide`/`async-direct`는 모든 AI 요청을 처리하는 조건이다. `sync-direct-bounded`/`sync-remote-two`는 일반 요청 보호와 AI 처리율의 교환을 비교한다. 둘을 구분하지 않고 일반 API p95만으로 서버 분리의 효과라고 결론 내리지 않는다.

HTTP 200이더라도 `embeddingApplied=false`이면 AI 성공으로 집계하지 않는다. 요청 예정·발사·HTTP 상태·AI 적용·폴백·공급자 시작/완료·발생기 지연을 함께 저장하고, 누락 요청과 전송/HTTP 오류가 있으면 실행을 실패시킨다. 별도 관리 포트에서 Tomcat busy와 스택을 관찰해 측정 자체가 포화된 요청 풀에 막히지 않게 한다.

## 해석 범위

일반 API 지연과 요청 스레드 점유가 함께 증가하면 외부 I/O 대기의 전파를 뒷받침한다. 비동기 또는 같은 프로세스의 동시 호출 제한으로도 보호된다면, 해당 현상만으로 별도 AI 서버가 필수라고 주장할 수 없다. 비동기는 요청 스레드 대기를 줄여도 외부 호출 수·연결·메모리·공급자 사용량을 무한대로 허용해도 된다는 뜻이 아니다.

실제 서비스 전환에는 운영 설정/동시 요청 수, 인증·DB·다른 API 경합, 호출 deadline/취소, 사용자별 허용량, AI 정확도와 폴백 품질을 별도로 검증해야 한다. 주간 회고는 별도 batch 앱이므로 이 테스트 결과를 그대로 적용하지 않는다.

참고: [Spring MVC 비동기 요청](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html), [Spring Boot 가상 스레드](https://docs.spring.io/spring-boot/reference/features/spring-application.html#features.spring-application.virtual-threads).
