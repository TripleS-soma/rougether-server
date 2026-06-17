# Backend Architecture

이 폴더는 Rougether 백엔드의 도메인 구조, API 계약, ERD 결정 사항을 기록합니다.

초기 구현 기준:

- Spring Boot 4.1
- Java 17
- MySQL + Flyway
- API prefix `/api/v1`
- Flyway 기반 migration
- 얇은 controller와 명확한 transaction 경계

## Next Decisions

다음 항목은 구현이 시작되면서 구체화합니다.

- 도메인 패키지 구조
- Facade 사용 범위
- MVP ERD 최종안
- [API 우선순위](api-priority.md)
