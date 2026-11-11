# AGENTS.md

이 파일은 Rougether 프로젝트에서 Codex 또는 AI 코딩 에이전트가 작업할 때 가장 먼저 읽는 루트 안내 문서입니다.

## 프로젝트 요약

Rougether는 루틴 수행을 개인 방 성장과 공동집 경험으로 연결하는 소셜 루틴 앱입니다. 이 저장소는 백엔드 서버를 담당합니다.

현재 MVP 방향은 다음과 같습니다.

- MVP 초기엔 핵심 흐름(루틴/투두, 방, 집 참여, 단체 미션) 도메인을 먼저 구체화합니다.
- 인증/인가는 MVP에 포함합니다(소셜 로그인 카카오·구글·애플 + JWT). 소유권 식별자로 권한(guard)을 실제 적용합니다.
- 도메인 계약(기능·API·데이터)의 정본은 [rougether-spec](https://github.com/TripleS-soma/rougether-spec) repo입니다. 이 repo 문서와 어긋나면 spec이 우선입니다.

## 팀 역할 / 도메인 담당

| 담당 | 범위 |
| --- | --- |
| 장진형 | 백엔드 — 방 · 집 · 뽑기 · 상점 도메인, 보상·미션 로직, 인프라, AI(LLM·비전) |
| 임채영 | 백엔드 — 루틴 · 투두 · 회원 도메인, DB 설계, 알림 |
| 최준서 | 프론트엔드 — React Native, UI/UX, 2D 화면 |

다른 담당자의 도메인 계약은 임의로 확정하지 않고, 필요한 값은 dependency 또는 open question으로 표시합니다.

## 먼저 읽을 문서

작업 성격에 따라 아래 문서를 읽고 시작합니다.

| 작업 성격 | 문서 |
| --- | --- |
| 백엔드 공통 규칙, 모듈 구조, 실행/테스트, migration | [docs/Codex/backend.md](docs/Codex/backend.md) |
| 프론트 연동 기준, 응답 형태, 이미지 로딩 | [docs/Codex/frontend.md](docs/Codex/frontend.md) |
| 방/공동집 API | [docs/Codex/domains/room-house.md](docs/Codex/domains/room-house.md) |
| 루틴/투두 API 의존성 | [docs/Codex/domains/routine-todo.md](docs/Codex/domains/routine-todo.md) |
| 에셋, 이미지, CDN/object key | [docs/Codex/domains/assets.md](docs/Codex/domains/assets.md) |

## 공통 작업 규칙

- 문서는 한국어로 작성합니다. 단, API path, JSON field, class name, package name, command는 기존 표기를 유지합니다.
- 사용자 확인 전에는 API 초안이나 기획 문서를 PR로 올리지 않습니다.
- 인증 구현이 뒤로 밀렸더라도 `userId`, `ownerUserId`, `houseId`, `roomUserId`, `membershipId` 같은 소유권 식별자는 유지합니다.
- DB에는 전체 CDN URL보다 asset key(`*_key`: `asset_key`, `cover_image_key`, `storage_key`)와 metadata를 저장하는 방향을 우선합니다.
- secret, token, private key, `.env`는 절대 커밋하지 않습니다.

## 검증 기준

백엔드 코드 변경 후 기본 검증:

```bash
./gradlew test
git diff --check
```

서버 smoke check가 필요할 때:

```bash
./gradlew bootRun
curl http://localhost:8080/api/v1/health
```

작업을 마칠 때 `bootRun` 같은 장기 실행 프로세스를 남겨두지 않습니다.

## 미결정 사항

미결정 사항은 spec repo에서 통합 관리합니다 → [rougether-spec](https://github.com/TripleS-soma/rougether-spec) repo의 `open-questions.md`.

참고: 일부 항목은 이미 결정되어 spec ERD에 반영되었습니다 (초대코드 = `house.invite_code` 컬럼, 다중 집 가입 허용, `house_goals`는 마스터 `goals` 참조 등).

## 운영 배포 안전 규칙 (2026-11-11)

- 운영 서비스 변경은 `.github/workflows/docker-publish.yml` 정식 배포 경로만 사용한다. 기본 배포 모드는 `blue-green`이어야 한다.
- 진행 중인 배포가 있으면 완료를 기다린다. SSM/SSH로 Nginx, systemd, 컨테이너, 환경 파일, 배포 상태를 직접 변경하거나 별도 복구 명령을 실행하지 않는다.
- 수동 `aws ssm send-command`, `start-session`, SSH 및 SDK 우회 실행을 금지한다. 진단은 검토된 읽기 전용 스크립트로 수행하고, 읽기 전용 진단에 변경 명령을 섞지 않는다.
- 배포 스크립트는 호스트의 `/run/rougether-deploy.lock` 커널 잠금을 변경 전에 획득한다. 잠금 파일 삭제, 다른 경로 지정, 함수 추출로 잠금 우회를 금지한다.
- 배포 중 오류가 나면 해당 배포의 롤백과 종료를 먼저 확인한다. 새 수정은 테스트와 PR 검증 후 다음 정식 배포로 반영한다.
- 배포 완료는 워크플로 성공과 실제 이미지 SHA, 사용자/어드민 health, 최근 1시간 오류 확인으로 판정한다.
- Claude 명령 훅은 직접 원격 명령을 거부한다. 텍스트 훅은 root 권한이나 임의 프로그램을 완전히 격리하지 못하므로 우회하지 않는다. Codex는 이 지침과 호스트 잠금을 적용하며, Claude 훅이 Codex에서도 실행된다고 가정하지 않는다.
