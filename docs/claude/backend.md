# Backend 작업 기준

이 문서는 Rougether 백엔드 작업의 공통 기준입니다.

## 기술 스택

- Java 17 (Gradle toolchain, foojay auto-provision)
- Spring Boot 4.1
- Gradle 멀티모듈
- Spring Web MVC
- Spring Security
- Spring Data JPA
- Flyway
- MySQL (운영) · H2 (로컬·테스트)

## 모듈 구조

저장소는 멀티모듈로 구성합니다.

```text
common/      프레임워크 비의존 공유 부품 (ErrorCode, ErrorResponse, BusinessException, util)
domain/      도메인별 Entity + Repository + Flyway migration. 스키마 단일 소스. JPA를 api 로 노출.
user-api/    사용자 대면 앱 :8080  /api/v1  OAuth2(카카오/구글/애플)+JWT(stateless)   [부팅]
admin-api/   운영자 대면 앱 :8081  /admin   아이디·비밀번호 + 세션(form login)        [부팅]
```

의존 방향은 `user-api`/`admin-api` → `domain` → `common`입니다. 앱끼리는 서로 의존하지 않습니다.

### 산출물을 어느 모듈에 두는가

| 산출물 | 모듈 | 패키지 |
| --- | --- | --- |
| Entity, Repository | `domain` | `com.triples.rougether.domain.<도메인>` |
| Flyway migration | `domain` | `src/main/resources/db/migration/V{n}__*.sql` |
| Service (비즈니스 로직) | 각 앱 | `...userapi.<도메인>` / `...adminapi.<도메인>` |
| Controller, DTO | 각 앱 | 위와 동일 |
| 공통 에러·util | `common` | `com.triples.rougether.common.*` |

현재 결정상 `domain`은 영속 계층(Entity+Repository)만 둡니다. Service는 각 앱에 둡니다. 같은 핵심 로직(재화 지급 등)을 admin도 쓰게 되면 그 Service만 `domain`으로 승격하는 것을 검토합니다. 도메인 패키지는 실제 구현이 시작되는 시점에 필요한 만큼 추가하고, 아직 확정되지 않은 구조를 미리 과하게 나누지 않습니다.

## 기본 API 기준

- API prefix는 user-api `/api/v1`, admin-api `/admin`입니다.
- Controller는 요청/응답 변환과 validation 진입점 중심으로 얇게 유지합니다.
- 여러 table을 함께 변경하는 쓰기 흐름에는 `@Transactional`을 사용합니다.
- 조회 서비스에는 `@Transactional(readOnly = true)`를 사용합니다.
- DB 변경은 Flyway migration으로 관리합니다 (`domain` 모듈).
- 응답 DTO는 frontend가 쓰기 좋은 형태로 설계하고, DB table을 그대로 노출하지 않습니다.
- 에러 응답은 spec `api.md` 형식 `{ code, message, fieldErrors }`를 따릅니다 (`common.error.ErrorResponse` / `BusinessException` + `ErrorCode`).

## Flyway migration 규칙

DB 변경은 `domain` 모듈의 `src/main/resources/db/migration/V{n}__*.sql`로 관리합니다.

- **버전 번호 충돌 금지 — merge 전 확인 (필수)**: 여러 피처 브랜치가 같은 `V{n}` 번호를 각자 만들면, 머지·배포 시 Flyway checksum mismatch로 **앱이 기동을 거부**합니다(health check 실패 → 롤백). 마이그레이션을 만들기 시작할 때, 그리고 **머지 전에** main의 최신 V번호를 확인하고 겹치지 않는 다음 번호를 사용합니다.
  ```bash
  git ls-tree -r --name-only origin/main | grep 'db/migration/V' | sort
  ```
- **이미 적용된 마이그레이션은 수정 금지 (불변)**: 적용된 `.sql` 내용을 고치면 checksum이 달라져 같은 오류가 납니다. 변경이 필요하면 반드시 새 버전으로 추가합니다.
- 충돌 증상·복구: 배포 로그에 `Migration checksum mismatch for migration version N`이 뜨면 그 앱이 기동 못 합니다. dev에서는 `flyway_schema_history`(DB)와 코드의 V번호를 대조해 정리합니다.

## 인증/인가 (MVP 포함)

`user-api`와 `admin-api`는 인증 주체·테이블·방식이 완전히 분리됩니다. 두 앱의 `SecurityFilterChain`은 공유하지 않으며, 토큰/세션 부품만 필요 시 `common`에 둡니다.

- **user-api**: 소셜 로그인(카카오·구글·애플) + JWT(stateless). 주체는 `users`/`oauth_accounts`.
  - 인증: 소셜 로그인으로 발급된 `userId` 기준.
  - 인가: 소유권 식별자(`userId`, `ownerUserId`, `roomUserId`, `houseId`, `membershipId`)로 권한(guard)을 실제 적용합니다.
  - `me` path는 인증된 사용자를 가리킵니다.
  - 토큰 만료·refresh 등 상세는 spec `open-questions.md`(P0) 참고.
- **admin-api**: 아이디/비밀번호 + 세션(form login). 주체는 별도 `admin_accounts` 테이블. user 인증과 섞지 않습니다.

## 테스트 정책

- 모든 도메인 기능에 의미 있는 테스트를 작성합니다 (getter/assertNotNull 도배 금지).
  - Service → 비즈니스 로직·트랜잭션·예외
  - Controller → `@WebMvcTest`로 요청/응답 계약·validation·에러 형식(`code`)
  - Repository → 커스텀 쿼리가 있을 때만 `@DataJpaTest`
- 위험영역은 트랜잭션·롤백·정합성까지 꼼꼼히 테스트합니다 (필수): 재화 지급/차감(`user_wallets`), 루틴/투두 완료·취소(코인+스트릭), 뽑기(`gacha` 확률·중복→다이아), 인증/인가(소유권 guard·admin 세션), 집 미션 정산(기여도·중복 수령).
- 스펙 미정값(enum 등)에 의존하는 부분은 테스트도 미루거나 최소화합니다 (값이 바뀌면 깨지므로).

## 코드 주석 스타일

- 코드 주석(Java/Gradle/JavaDoc)은 한국어 음슴체(~함/~음)로 작성합니다.
- 문서 산문(이 docs, 마크다운 등)은 평서/존대 문장으로 작성합니다. 주석 음슴체를 산문에 쓰지 않습니다.

## 구현 전 확인

작업 전 다음을 확인합니다.

- 이 작업이 백엔드 공통 작업인지, 특정 도메인 작업인지
- DB migration이 필요한지
- frontend 응답 형태에 영향을 주는지
- 임채영 담당 루틴/투두 API와 dependency가 있는지
- 인증이 없더라도 소유권 필드가 보존되는지

## 검증

기본 검증:

```bash
./gradlew test
git diff --check
```

서버 smoke check:

```bash
./gradlew :user-api:bootRun     # :8080
curl http://localhost:8080/api/v1/health

./gradlew :admin-api:bootRun    # :8081
curl http://localhost:8081/admin/health
```

작업을 마칠 때 `bootRun` 같은 장기 실행 프로세스를 남겨두지 않습니다.
