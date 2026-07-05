# rougether-server

루틴 수행을 개인 방 성장과 공동 집 경험으로 연결하는 소셜 루틴 앱 백엔드 서버입니다.

## Tech Stack

- Java 25
- Spring Boot 4.1
- Gradle
- Spring Web MVC
- Spring Security
- Spring Data JPA
- Flyway
- MySQL
- H2 for local test/dev

## Package Direction

```text
com.triples.rougether
  global
    api
    config
    error
```

현재는 공통 설정과 헬스체크 중심의 최소 구조로 시작합니다.

## Run Locally

기본 프로파일은 H2 인메모리 DB를 사용합니다.

```bash
./gradlew bootRun
```

헬스체크:

```bash
curl http://localhost:8080/api/v1/health
```

## Run Tests

```bash
./gradlew test
```

## Team Rules

커밋, 브랜치, PR 규칙은 [CONTRIBUTING.md](CONTRIBUTING.md)를 따릅니다.

## MySQL Profile

MySQL로 실행할 때는 환경변수를 지정하고 `mysql` 프로파일을 켭니다.

```bash
export DB_URL='jdbc:mysql://localhost:3306/rougether?serverTimezone=Asia/Seoul&characterEncoding=UTF-8'
export DB_USERNAME='root'
export DB_PASSWORD='password'

./gradlew bootRun --args='--spring.profiles.active=mysql'
```

## API Prefix

기본 API prefix는 `/api/v1`입니다.
