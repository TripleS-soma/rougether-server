# Backend Architecture

이 폴더는 Rougether 백엔드의 도메인 구조, API 계약, ERD 결정 사항을 기록합니다.

초기 구현 기준:

- Spring Boot 4.1
- Java 25
- MySQL + Flyway
- API prefix `/api/v1`
- Flyway 기반 migration
- 얇은 controller와 명확한 transaction 경계

## 다음 결정 사항

다음 항목은 구현이 시작되면서 구체화합니다.

- 도메인 패키지 구조
- Facade 사용 범위
- MVP ERD 최종안
- API 우선순위

## 문서

- AI 작업 진입점: [`CLAUDE.md`](../../CLAUDE.md)
- 백엔드 공통 기준: [`docs/claude/backend.md`](../claude/backend.md)
- 프론트 연동 기준: [`docs/claude/frontend.md`](../claude/frontend.md)
- 방/공동집 도메인: [`docs/claude/domains/room-house.md`](../claude/domains/room-house.md)
- 루틴/투두 도메인: [`docs/claude/domains/routine-todo.md`](../claude/domains/routine-todo.md)
- 에셋/이미지/CDN 도메인: [`docs/claude/domains/assets.md`](../claude/domains/assets.md)
