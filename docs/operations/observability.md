# 서버 관측성 운영 기준

이 문서는 user-api의 에러 추적(Sentry), 요청 지표(Micrometer), 요청 ID가 어떻게 켜지고 어디서 보는지, 배포 때 무엇을 넣어야 하는지를 정리합니다. 앱 쪽 기준은 `rougether-mobile/docs/observability.md`이고, 이 문서의 환경 이름과 태그는 그 문서와 맞춥니다(mobile #1376).

## 무엇이 켜지나

- **Sentry 에러 추적**: `SENTRY_DSN`이 있을 때만 켜집니다. 비어 있으면 SDK 자체가 비활성이라 로컬·테스트·미설정 배포에 영향이 없습니다.
  - 보내는 것: `GlobalExceptionHandler`가 처리한 예상치 못한 예외(`handleUnexpected`)와 5xx 비즈니스 오류. 태그 `endpoint`(메서드 + 매칭 경로 패턴), `request_id`.
  - 보내지 않는 것: 4xx 비즈니스 오류·검증 실패(정상 흐름), 요청 본문(`max-request-body-size: none`), 기본 PII(`send-default-pii: false`). 로그 → Sentry 이벤트 연동은 꺼서(`sentry.logging.enabled: false`) `log.error`와 이중 보고를 막습니다.
  - Webex 운영 알림(`OperationalAlertNotifier`)은 그대로 두고 Sentry와 병행합니다. Webex는 즉시 알림, Sentry는 그룹핑·스택·빈도 분석 용도입니다.
- **Sentry 트레이싱**: 같은 DSN으로 HTTP 요청 트랜잭션을 `SENTRY_TRACES_SAMPLE_RATE`(기본 0.2) 비율로 수집합니다.
- **Micrometer 지표**: `http.server.requests`의 p50/p95/p99와 퍼센타일 히스토그램, SLO 버킷(100ms·300ms·1s), HikariCP 커넥션 풀, JVM 지표가 등록됩니다. 공통 태그 `application`, `environment`. Prometheus 레지스트리가 들어 있지만 **엔드포인트는 기본 비공개**입니다(아래 보안 절).
- **요청 ID**: 모든 응답에 `X-Request-Id`가 붙습니다. 클라이언트가 안전한 형식(영숫자·`.`·`_`·`-`, 8~64자)으로 보내면 이어받고, 아니면 UUID를 만듭니다. 같은 값이 로그 줄 앞(`[requestId]`)과 Sentry 태그 `request_id`에 남습니다. CORS `Access-Control-Expose-Headers`로 노출해 웹앱도 읽을 수 있습니다 — 앱 버그 제보(mobile #1162)가 이 값을 첨부하면 로그·Sentry 이벤트로 바로 찾아갑니다.

## 배포 때 필요한 값

현재 배포는 hold 모드라 아래 값은 수동 배포 담당자가 넣습니다. 전부 비어 있어도 서버는 정상 기동하며 기능만 꺼집니다.

- `SENTRY_DSN`: Sentry 프로젝트(서버용, 앱 프로젝트와 분리 권장)의 DSN. SSM 파라미터 예: `/rougether-dev/sentry/dsn` (SecureString). `deploy/scripts`의 컨테이너 env 주입 목록과 `docker-publish.yml`의 파라미터 치환 목록에 함께 추가해야 컨테이너에 전달됩니다.
- `SENTRY_ENVIRONMENT`: 비우면 `ROUGETHER_ENVIRONMENT`(기본 `dev`)를 씁니다. 운영은 `production`으로 두어 앱의 `production` 레인과 같은 이름으로 필터합니다.
- `SENTRY_TRACES_SAMPLE_RATE`: 기본 0.2. 무료 플랜 한도(월 스팬 5M) 안에서 조정합니다.
- `MANAGEMENT_ENDPOINTS`: 기본 `health,info`. 지표 수집기를 붙일 때만 바꿉니다.

## 지표 공개와 보안

- 공개 포트(8080)의 actuator 노출은 기본 `health,info`뿐입니다.
- `MANAGEMENT_ENDPOINTS`에 `prometheus`를 넣더라도 `SecurityConfig`가 `/actuator/health`·`/actuator/info` 외에는 인증을 요구하므로 인터넷에서 익명으로 읽을 수 없습니다.
- 수집기를 실제로 붙이는 절차(인프라 결정 후):
  1. `management.server.port`를 별도 포트(예: 9090)로 지정하는 환경변수를 추가하고, 그 포트를 보안그룹에서 수집기(같은 VPC)만 허용합니다.
  2. 관리 포트용 보안 규칙에서 `/actuator/prometheus`만 허용하도록 `SecurityConfig`에 관리 포트 조건을 추가합니다(공개 포트 규칙과 분리).
  3. 수집처 후보: Amazon Managed Prometheus + Grafana, 또는 OTLP로 CloudWatch. Spring Boot 4에는 CloudWatch 레지스트리 자동 구성이 없어 OTLP(`management.otlp.metrics.export.*`)가 CloudWatch로 가는 표준 경로입니다.

## 앱 트레이스 전파를 켜는 순서

앱은 지금 `tracePropagationTargets: []`로 API 요청에 `sentry-trace`·`baggage`를 붙이지 않습니다. 서버가 그 헤더를 CORS에서 허용하지 않으면 웹앱 preflight가 실패해 **API 전체가 막히기** 때문입니다.

1. 이 변경(CORS 허용 헤더 `sentry-trace`·`baggage`·`X-Request-Id`)이 운영에 배포됐는지 확인합니다. 브라우저 콘솔에서 `app.rougether.com` 출처로 preflight를 보내 `Access-Control-Allow-Headers`를 확인하거나, `curl -X OPTIONS -H 'Origin: https://app.rougether.com' -H 'Access-Control-Request-Method: GET' -H 'Access-Control-Request-Headers: sentry-trace,baggage' https://<api>/api/v1/routines -i`.
2. 서버 `SENTRY_DSN`을 넣어 서버 트랜잭션이 수집되는지 확인합니다.
3. 그다음에만 앱의 `tracePropagationTargets`에 API 호스트를 추가해 OTA로 내보냅니다. 순서를 바꾸면 웹앱이 먹통이 됩니다.

## 조사할 때

- 사용자 제보에 `X-Request-Id`가 있으면: 서버 로그에서 `[<id>]`로 검색 → 같은 요청의 로그 줄 전체, Sentry에서 `request_id:<id>` 태그 검색 → 스택.
- 지연 조사: `http.server.requests`를 `uri`·`status`별 p95로 봅니다. HikariCP `hikaricp.connections.pending`이 오르면 풀 고갈(활동 기록 묶음 커밋 #393 참고)을 먼저 의심합니다.
