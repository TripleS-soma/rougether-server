# Contributing Guide

Rougether 서버 레포의 커밋, 브랜치, PR 규칙입니다.

## 1. Branch Rules

기본 브랜치는 `main`입니다. 기능 개발은 별도 브랜치에서 진행합니다.

브랜치 이름:

```text
feat/{issue-number}-{short-name}
fix/{issue-number}-{short-name}
refactor/{issue-number}-{short-name}
docs/{issue-number}-{short-name}
chore/{short-name}
```

예시:

```text
feat/12-routine-complete
fix/18-refresh-token
docs/api-convention
chore/init-project
```

이슈 번호가 아직 없으면 짧은 설명만 사용해도 됩니다.

## 2. Commit Rules

커밋 메시지는 Conventional Commits 형태를 사용합니다. scope는 필수로 쓰지 않습니다.

```text
type: summary
```

예시:

```text
feat: 루틴 완료 API 추가
fix: refresh token 만료 처리 수정
refactor: 방 배치 검증 로직 분리
docs: ERD 결정 사항 정리
chore: Spring Boot 프로젝트 초기 설정
```

### Type

| Type | 의미 |
| --- | --- |
| `feat` | 새 기능 |
| `fix` | 버그 수정 |
| `refactor` | 동작 변화 없는 구조 개선 |
| `docs` | 문서 수정 |
| `test` | 테스트 추가/수정 |
| `chore` | 빌드, 설정, 잡무 |
| `build` | Gradle, dependency, packaging 변경 |
| `ci` | GitHub Actions 등 CI 변경 |
| `style` | 포맷팅, 코드 스타일 변경 |
| `perf` | 성능 개선 |

### Summary

- 한국어 사용 가능
- 50자 안팎 권장
- 마침표로 끝내지 않음
- 한 커밋에는 하나의 목적만 담기
- `WIP`, `temp`, `fix bug` 같은 의미 없는 제목 지양

## 3. Pull Request Rules

PR은 기본적으로 작은 단위로 올립니다. 한 PR이 너무 커지면 리뷰가 느려지고 버그를 놓치기 쉽습니다.

권장 크기:

- 기능 PR: 한 유스케이스 또는 한 API 단위
- 수정 PR: 한 버그 또는 한 원인 단위
- 문서 PR: 한 주제 단위

PR 제목도 커밋 메시지와 같은 형식을 권장합니다.

```text
feat: 루틴 완료 API 추가
docs: 백엔드 아키텍처 문서 추가
```

## 4. PR Checklist

PR 작성자는 아래를 확인합니다.

- 관련 이슈 또는 작업 목적을 적었다.
- 변경 내용을 요약했다.
- 테스트 결과를 적었다.
- DB migration이 필요한 경우 이유와 영향 범위를 적었다.
- API 변경이 있는 경우 요청/응답 예시 또는 문서 링크를 적었다.
- API 계약이 바뀌는 경우 🔔 API 변경 섹션을 추가했다 (아래 참고).
- 보안 정보, 토큰, 개인키, `.env` 파일을 커밋하지 않았다.

본문 템플릿:

```markdown
## 작업 목적
<관련 이슈 또는 목적>

closes #<이슈번호>   <!-- 관련 구현 이슈가 있으면. 머지 시 자동 close. 없으면 이 줄 생략 -->

## 변경 내용
<요약>

## 테스트
<./gradlew test 결과>

## 체크
- [ ] DB migration 필요 시 이유·영향 범위 기재
- [ ] API 변경 시 요청/응답 예시 또는 문서 링크
- [ ] secret·토큰·개인키·.env 미커밋
- [ ] 리뷰 필수 여부: <필수 | 셀프 머지 가능>
```

### API 변경 섹션 (프론트 알림)

API 계약이 바뀌는 PR은 본문 **맨 아래**에 아래 섹션을 추가합니다. 대상: 엔드포인트 path·메서드 변경, 요청/응답 필드 추가·삭제·의미 변경, 에러코드 추가, 기존 동작이 바뀌는 것(규칙 변화 포함). 내부 리팩토링·테스트·Swagger 문구만 다듬은 변경은 제외합니다.

```markdown
## 🔔 API 변경

| 구분 | 변경 |
| --- | --- |
| <동작 변경 | 필드 추가 | 에러코드 추가 | ...> | `<METHOD> <path>` — <무엇이 어떻게 바뀌었는지> |

프론트 반영 포인트: <받는 사람이 무엇을 해야 하는지 한 줄>
```

- 섹션에 **@멘션은 넣지 않습니다** — PR이 머지되면 `.github/workflows/api-change-notify.yml`이 본문의 `🔔 API 변경` 마커를 감지해 프론트 담당(@evan7484)을 댓글로 멘션합니다(알림은 머지 시점 한 번만).
- PR을 올린 뒤 API 변경이 추가로 생기면 본문 섹션을 갱신하고, 댓글로 추가분을 알립니다(본문 수정은 재알림이 가지 않습니다).
- "프론트 반영 포인트" 한 줄을 반드시 붙입니다 — 변경 나열만 하지 않습니다.

## 5. Review Rules

백엔드 담당자가 2명인 점을 고려해 모든 PR에 리뷰를 강제하지 않습니다. 대신 변경 위험도에 따라 리뷰 기준을 나눕니다.

### 리뷰 필수

아래 변경은 다른 백엔드 담당자 1명 이상이 확인한 뒤 머지합니다.

- 인증/인가, 토큰, 세션 관련 변경
- DB migration, 테이블 관계, 인덱스 변경
- 재화, 보상, 뽑기처럼 데이터 정합성이 중요한 변경
- 루틴 완료, 스트릭, 방 성장처럼 여러 도메인이 함께 바뀌는 변경
- API 요청/응답 계약 변경
- 운영 환경 설정, 배포, 보안 설정 변경

### 셀프 머지 가능

아래 변경은 작성자가 체크리스트를 확인한 뒤 직접 머지할 수 있습니다.

- 문서 수정
- 오타 수정
- 테스트 코드만 보강
- 단순 설정 정리
- 동작 변화 없는 작은 리팩터링

리뷰어는 동작 버그, 데이터 정합성, 보안, 테스트 누락을 우선으로 봅니다. 단순 취향 차이는 blocking comment보다 suggestion으로 남깁니다.

긴급 수정은 먼저 머지할 수 있지만, 머지 후 다른 백엔드 담당자에게 변경 내용을 공유합니다.

## 6. Merge Rules

기본 머지 방식은 일반 merge를 사용합니다.

이유:

- PR 안의 커밋 흐름이 main에 그대로 남습니다.
- 멘토와 팀원이 작업 과정을 더 정확하게 확인할 수 있습니다.
- 기능 구현, 테스트 보강, 리뷰 반영 과정을 커밋 단위로 추적할 수 있습니다.

따라서 작업 중 커밋도 가능한 한 의미 있는 단위로 나눕니다.

단, 실수 커밋이나 의미 없는 중간 커밋이 너무 많은 경우에는 PR 안에서 정리한 뒤 merge합니다.

## 7. Before Push

로컬에서 최소한 아래 명령을 통과시킨 뒤 push합니다.

```bash
./gradlew test
```

서버 실행 확인이 필요한 경우:

```bash
./gradlew bootRun
curl http://localhost:8080/api/v1/health
```
