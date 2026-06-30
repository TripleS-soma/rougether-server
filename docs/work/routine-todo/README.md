# routine-todo 작업 롤업
출처: ../rougether-spec/domains/routine-todo/

임채영 담당. 루틴 · 투두 · 카테고리 도메인 구현 계획·진행 현황.

| 기능 | 계획 파일 | 미해결 | 상태 |
| --- | --- | --- | --- |
| 카테고리 API | [categories.md](categories.md) | 0 | 완료 (verifier PASS·reviewer blocking 없음, PR 리뷰 필수) |
| 루틴 CRUD API | [routines.md](routines.md) | 0 | 완료 (verifier PASS·reviewer blocking 없음, PR 리뷰 필수) |
| 루틴 완료/취소 API | [routine-logs.md](routine-logs.md) | 0 | 완료 (verifier PASS·reviewer blocking 없음, 컨트롤러는 RoutineController로 병합, 위험영역·PR 리뷰 필수) |
| 투두 CRUD+완료/취소 API | [todos.md](todos.md) | 0 | 완료 (테스트 PASS, 음수잔액=방어적 가드 해결·동시성=현행 유지 후속, 위험영역·PR 리뷰 필수) |
| 사진 인증 API | [photo-verifications.md](photo-verifications.md) | 0 | 확정 (routine-logs 선행, `/run-plan routine-todo/photo-verifications` 대상) |
| 오늘 현황(/today) API | [today.md](today.md) | 0 | 완료 (테스트 PASS, 읽기 전용) |

## 범례
- **상태**: `계획 중`(미해결 남음) / `확정`(미해결 0, /run-plan 대상) / `구현 중` / `완료`
