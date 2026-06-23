# Backend 작업 기준

이 문서는 Rougether 백엔드 작업의 공통 기준입니다.

## 기술 스택

- Java 17
- Spring Boot 4.1
- Gradle
- Spring Web MVC
- Spring Security
- Spring Data JPA
- Flyway
- MySQL
- 로컬 개발 및 테스트용 H2

## 기본 API 기준

- API prefix는 `/api/v1`입니다.
- Controller는 요청/응답 변환과 validation 진입점 중심으로 얇게 유지합니다.
- 여러 table을 함께 변경하는 쓰기 흐름에는 `@Transactional`을 사용합니다.
- 조회 서비스에는 `@Transactional(readOnly = true)`를 사용합니다.
- DB 변경은 Flyway migration으로 관리합니다.
- 응답 DTO는 frontend가 쓰기 좋은 형태로 설계하고, DB table을 그대로 노출하지 않습니다.

## 패키지 방향

현재 저장소는 최소 구조로 시작했습니다.

```text
com.triples.rougether
  global
    api
    config
    error
```

도메인 패키지는 실제 구현이 시작되는 시점에 필요한 만큼 추가합니다. 아직 확정되지 않은 도메인 구조를 미리 과하게 나누지 않습니다.

## 인증/인가 (MVP 포함)

멘토 결정에 따라 인증/인가를 MVP에 포함합니다. **소셜 로그인(카카오·구글·애플) + JWT** 기반.

- 인증: 소셜 로그인으로 발급된 `userId` 기준. 로그인 수단은 `oauth_accounts` 테이블(spec ERD 참고 — ERDCloud 정본 반영 필요).
- 인가: 소유권 식별자(`userId`, `ownerUserId`, `roomUserId`, `houseId`, `membershipId`)로 권한(guard)을 실제 적용합니다.
- `me` path는 인증된 사용자를 가리킵니다.
- 토큰 만료·refresh 등 상세는 spec `open-questions.md`(P0) 참고.

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
./gradlew bootRun
curl http://localhost:8080/api/v1/health
```
