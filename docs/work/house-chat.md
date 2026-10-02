# 집 채팅 구현 및 검증

집 구성원 텍스트 채팅, 기록, 재접속 복구, 읽음 표시를 구현합니다. 첫 배포는 기존 user-api 인스턴스에서 함께 운영하며, 필요 시 채팅 수신부를 분리할 수 있도록 chat 도메인 경계를 둡니다. 모바일 연동은 별도 작업입니다.

API 계약은 rougether-spec의 `domains/chat/api.md`, 데이터 모델은 `domains/chat/data.md`입니다. 이 문서는 서버 구현과 운영 연결 조건을 설명합니다.

## 구조

- domain: ChatRoom / ChatMessage / ChatReadState 및 Repository, V77 migration.
- user-api/chat: HTTP command/query, 집 멤버십 접근 정책, WebSocket 수신, Redis 변경 전파.
- 쓰기는 HTTP JWT 인증과 DB 트랜잭션을 사용합니다. `SUBSCRIBE.includeMessages=true`인 연결에는 커밋된 메시지 본문을 `MESSAGE_CREATED`로 직접 전달합니다. 옵션을 생략한 기존 연결은 `ROOM_UPDATED`와 HTTP 조회를 유지합니다. 최초 기록·재접속 복구는 두 모드 모두 HTTP 순서 커서를 사용합니다.
- 메시지 순서·읽음 상태는 MySQL이 정본입니다. 같은 UUID 재시도는 같은 메시지를 반환하고 읽음 위치는 감소하지 않습니다.
- 스키마/메시지/API를 chat 도메인으로 분리했습니다. 미래 전체 채팅은 별도 room type과 접근 정책을 추가하되 기존 집 채팅 저장 구조를 재사용할 수 있습니다.

## 단일/다중 인스턴스

단일 인스턴스는 Redis 없이 실행합니다. 로컬 커밋 후 송신 작업을 즉시 제출하며 250ms 동기화 주기를 기다리지 않습니다. Redis를 끈 다중 인스턴스에서도 각 노드의 약 5초 DB 동기화가 정본을 따라가지만, 즉시 서버 간 전파에는 아래 공통 Redis 설정이 필요합니다.

```properties
chat.redis.enabled=true
chat.redis.host=<shared-host>
chat.redis.port=6379
chat.redis.password=<secret injection>
chat.redis.ssl-enabled=true
```

환경변수는 `CHAT_REDIS_ENABLED`, `CHAT_REDIS_HOST`, `CHAT_REDIS_PORT`, `CHAT_REDIS_PASSWORD`, `CHAT_REDIS_SSL_ENABLED`입니다. 기존 일일 활동 캐시와 연결/설정을 분리합니다. Redis 채널은 `rougether:chat:room-changed:v1`입니다. 환경별 Redis를 분리해야 합니다.

Redis는 구독 시작 시 연결 가능해야 합니다. 실행 중 발행/구독 장애가 생기면 저장은 유지하고 5초 DB 동기화로 복구합니다. Redis Pub/Sub 자체를 전달 보장으로 취급하지 않습니다. 클라이언트도 재접속 시 `after`를 조회해야 합니다.

소켓별 송신 작업은 직렬로 처리합니다. 본문 모드는 연결의 마지막 소켓 쓰기 순서 이후를 DB에서 최대 50개씩 읽어 보내고, 남은 페이지는 대기열 뒤에 다시 제출합니다. 커밋/Redis 변경 신호를 합쳐도 DB 커서가 본문을 보존하므로 전송 중 도착한 메시지를 생략하지 않습니다. READY는 최초 기준 순서를 전달하고, 그 이전 기록은 HTTP 조회로 복구합니다. 서버의 소켓 쓰기 완료는 단말 수신 확인이 아니므로 클라이언트는 마지막 연속 수신 위치를 별도로 보존해야 합니다.

송신은 기존 4개 작업 스레드·256개 작업 대기열로 제한합니다. 연결당 실행/대기 작업은 하나이며, 대기열에는 메시지 본문 대신 연결 작업만 보관합니다. 소켓 쓰기가 3초 이상 막히면 약 1초 주기의 감시 작업이 연결을 종료하고 클라이언트가 재접속·HTTP 커서로 복구합니다. 송신 대기열 초과는 1013, 송신 실패/지연은 1011입니다. 프레임을 내보내기 직전 현재 집 구성원 권한과 JWT를 다시 검사해, 앞선 쓰기가 지연되는 동안 강퇴된 구성원에게 다음 본문을 보내지 않습니다.

노드당 연결 5,000개, 사용자당 노드별 연결 10개, 인증 프레임 8KiB, 인증 대기 10초 제한은 유지합니다. 이 수치는 보호용 설정값이며 부하 검증으로 보장한 처리량이 아닙니다. 구독별 DB 조회와 프레임별 인가 조회 비용이 있으므로 큰 방 도입 전에는 DB 쿼리·송신 지연·대기열 사용량을 측정하고 fan-out 구조를 다시 검토해야 합니다.

집 채팅은 소규모 구성원을 전제로 합니다. 전체 채팅 도입 시 현재 모든 구성원의 읽음 위치를 반환하는 계약은 바꾸고, 큰 단일 채팅방의 DB 직렬화·fan-out 비용을 측정해야 합니다. 이번 작업에서 대규모 성능을 검증했다고 주장하지 않습니다.

## 모바일 전환

1. 기존 API에 `includeMessages` 옵션과 `MESSAGE_CREATED`를 추가한 서버를 먼저 배포합니다.
2. 모바일이 SUBSCRIBE에 `includeMessages=true`를 보내고 READY의 같은 플래그를 확인합니다. 옵션을 생략한 기존 앱은 같은 형식의 READY/ROOM_UPDATED만 받습니다.
3. `MESSAGE_CREATED.message`는 HTTP와 같은 DTO이며 즉시 화면에 병합합니다. 본문을 정상 수신한 매 메시지마다 HTTP 조회를 하지 않습니다. 발신 HTTP 응답보다 이벤트가 먼저 올 수 있어 발신자·`clientMessageId`로 전송 중 말풍선과 연결합니다.
4. 초기 기록 조회 중 들어온 본문은 임시 보관해 HTTP 결과와 합치고 `(roomId, sequence)` 또는 `messageId`로 중복을 제거합니다. 순서에 틈이 있을 때만 마지막 연속 수신 위치부터 `after`로 복구합니다. READY/ROOM_UPDATED의 마지막 순서만 보고 커서를 건너뛰지 않습니다.
5. ROOM_UPDATED는 읽음/구성원 갱신과 누락 감지에 사용합니다. 실제 화면에 표시된 위치만 기존 read API로 보냅니다. 재접속에는 백오프와 jitter를 적용합니다.

모바일 구현은 이 백엔드 변경에 포함하지 않습니다. 서버 배포만으로 구버전 앱의 수신 흐름이 바뀌지는 않습니다. Redis 채널과 DB 스키마가 같으므로 서버 간 알림 계약은 유지됩니다.

## 배포 연결 조건

- Nginx Packer 설정, bootstrap 스크립트와 CI 배포 스크립트에 `/api/v1/chat/ws`의 HTTP/1.1 Upgrade 전달 경로를 추가했습니다.
- 실제 라우터에 해당 설정을 반영하고 WebSocket 연결을 검증해야 합니다. 애플리케이션 배포만으로 기존 Nginx 설정이 갱신됐다고 간주하지 않습니다.
- 기존 CloudFront user-api의 AllViewerExceptHostHeader 정책은 WebSocket 관련 헤더를 전달하는 구성이지만 실제 wss 경로는 배포 후 확인해야 합니다.
- `cors.allowed-origins`가 브라우저 소켓 Origin도 제한합니다. 네이티브 앱도 첫 프레임 JWT 인증을 수행합니다.
- 토큰이나 메시지 본문은 로그에 남기지 않습니다.

## 검증

```bash
./gradlew compileJava
./gradlew :user-api:test --tests '*chat.*'
./gradlew test
bash -n deploy/scripts/bootstrap-blue-green-router.sh
bash deploy/scripts/test-bootstrap-blue-green-router.sh
bash .github/scripts/test-deploy-ec2-with-rollback.sh
git diff --check
```

실제 MySQL과 WebSocket 통합 테스트는 방 분리·비구성원 차단·전송 멱등성·동시 전송·읽음 역행 방지·페이징/누락 복구·롤백·강퇴/탈퇴·봇 제외·HTTP 검증·소켓 전달/종료를 확인합니다. Redis 테스트는 실제 Redis에 독립 연결한 두 소켓 처리기가 같은 DB 조회 stub을 사용해 본문을 전달하며, 로컬/Redis 중복 신호가 메시지를 중복시키지 않는지 확인합니다. 별도로 토큰 만료·인증 대기시간·실행 중 Redis 발행 실패·주기 본문 복구를 검사합니다. DB와 실제 WebSocket은 MySQL 통합 테스트에서 검증합니다.

실제 두 user-api 프로세스의 장시간 부하 검증은 별도 단계입니다. AWS 배포 검증은 배포 실행 결과와 공개 wss 경로에서 확인합니다.

공식 구현 참고: [Spring WebSocket API](https://docs.spring.io/spring-framework/reference/web/websocket/server.html), [Spring Data Redis Pub/Sub](https://docs.spring.io/spring-data/redis/reference/redis/pubsub.html).

### 최종 로컬 검증 결과 (2026-09-22 KST)

- `./gradlew compileJava`: 전체 모듈 성공.
- 최종 `./gradlew test`: BUILD SUCCESSFUL. XML 결과 합계 2,308개 중 2,301개 통과, 7개 스킵, 실패/오류 0개.
- 채팅 신규 테스트: 19개 모두 통과. 실제 MySQL·WebSocket 및 Redis listener 간 전파를 포함합니다.
- Nginx bootstrap 스크립트 문법/회귀 테스트, 서버·스펙의 `git diff --check`: 성공.
- 최신 origin/main의 마지막 migration은 V76으로 확인해 V77 충돌이 없습니다.
- 검증용 앱/테스트 프로세스는 종료했습니다. 이 결과는 로컬 검증이며 AWS 배포 성공을 의미하지 않습니다.

### 본문 직접 수신 검증 결과 (2026-10-02 KST)

- `./gradlew compileJava`: 전체 모듈 성공.
- `./gradlew test`: BUILD SUCCESSFUL. XML 합계 2,537개 중 2,529개 통과, 8개 스킵, 실패/오류 0개.
- 채팅 테스트 36개 모두 통과. 스케줄러 없는 본문 전달·READY 경합·전송 중 신호·페이지 처리·권한 재검사·지연/과부하·MySQL/WebSocket·두 Redis 연결을 포함합니다.
- 서버/스펙 `git diff --check`, 스펙 JSON 예제 파싱 성공.
- 로컬 검증 결과이며 운영 배포나 모바일 연동 완료를 의미하지 않습니다.
