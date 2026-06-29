# 루틴(routines) CRUD API 구현 계획
출처: ../rougether-spec/domains/routine-todo/ · 생성: 2026-06-28

내 루틴 목록 조회 / 단건 조회 / 등록 / 수정 / 삭제(soft). 모든 엔드포인트는 JWT 인증 + `user_id` 소유권 guard 적용. 응답 envelope 없음, 목록은 `items` 배열. 공개 범위는 **카테고리 단위**라 루틴에는 `visibility` 없음(features.md 우선, 엔티티에도 이미 없음).

## 결정값 (확정)
- **visibility 미포함**: 요청·응답 모두 visibility 없음. 공개 범위는 category를 따름. (api.md 초안의 routine `visibility`는 features.md·현 엔티티 기준 폐기)
- **categoryId optional + 소유권 검증**: 등록/수정 시 `categoryId` 지정하면 `findByIdAndUserIdAndDeletedAtIsNull`로 본인 카테고리인지 검증, 아니면 `CATEGORY_NOT_FOUND`(404). 미지정이면 미분류(category=null). 카테고리 삭제 시 NULL 전이는 기존 `RoutineRepository.clearCategory`가 처리(이 범위 무관).
- **repeat_type 허용값**: `DAILY` / `WEEKLY` (`WEEKLY_COUNT`는 기능 제거됨 — 2026-06-28 결정). `repeat_days`(JSON): DAILY→null, WEEKLY→`{"daysOfWeek":["MON",...]}`. 요일 `MON`~`SUN`. **MVP에서는 JSON 문자열을 그대로 저장·전달**(구조 강검증은 후속). `repeat_type`만 값 검증(둘 중 하나).
- **authType 허용값**: `CHECK` / `PHOTO` (`AuthType` enum 재사용, 확정).
- **status**: 초기값 `ACTIVE`. 등록 시 클라이언트가 지정 못 함(서버가 ACTIVE 주입). 수정에서 status 변경은 이 범위 밖(별도). `RoutineStatus`(ACTIVE/PAUSED/ARCHIVED) 재사용.
- **삭제(soft delete)**: `deleted_at` 설정만. `routine_logs`는 별도 변경 없이 그대로 둠 — GET routines가 `deletedAtIsNull`로 제외하므로 자연 숨김(보존 정책 미정이라 로그는 보존).
- **GET 목록 필터**: `categoryId?`, `status?` 둘 다 optional. 미지정이면 deleted 아닌 전체. status 지정 시 해당 status만.
- **응답 필드**: `id`, `title`, `categoryId`(nullable), `authType`, `status`, `repeatType`, `repeatDays`, `scheduledTime`, `startsOn`, `endsOn`. 시각·날짜는 ISO-8601.

## 미해결 (구현 전 확정 필요)
- 없음

## 사전 확인 (스키마는 이미 존재)
- `routines` 테이블·`fk_routine_user`·`fk_routine_category`·`idx_routines_user_status`는 V1에 존재. **신규 migration 불필요.**
- `Routine` 엔티티·`RoutineRepository`는 이미 존재 → 확장만 함. `AuthType`/`RoutineStatus` enum 존재.

## 스텝

### domain 모듈
- [x] domain: `Routine` 엔티티 확장 — 생성 정적 팩토리 `create(user, category, title, authType, repeatType, repeatDays, scheduledTime, startsOn, endsOn)` (status=ACTIVE 주입), 수정 메서드 `update(...)`(부분 변경, null 필드 미변경), `changeCategory(Category)`, `softDelete(Instant)` 추가.
- [x] domain: `RoutineRepository` 확장 — `findByIdAndUserIdAndDeletedAtIsNull(id, userId)`(소유권 guard 단건), 목록 조회용 `findByUserIdAndDeletedAtIsNull(userId)` + 필터 변형. 기존 `findByUserIdAndStatusAndDeletedAtIsNull`(현재 status가 String 파라미터)은 `RoutineStatus` 타입/정렬(`OrderByScheduledTimeAsc` 등) 정리. `clearCategory`는 그대로 둠.
- [x] migration: **불필요** (routines 테이블·FK·인덱스 모두 V1 존재)

### user-api 모듈
- [x] user-api: `RoutineErrorCode` — `ROUTINE_NOT_FOUND`(404). 미존재·타인 소유 모두 404로 통일. 카테고리 소유권 실패는 categories의 `CATEGORY_NOT_FOUND` 재사용(또는 `INVALID_CATEGORY` 400 중 택1 — 구현 시 categories ErrorCode 재사용 우선).
- [x] user-api: DTO — `RoutineResponse`(위 응답 필드), `RoutineListResponse`(items 배열), `RoutineCreateRequest`(title @NotBlank @Size(max=160), categoryId?, authType @NotNull, repeatType?(셋 중 하나 검증), repeatDays?, scheduledTime?, startsOn?, endsOn?), `RoutineUpdateRequest`(전부 optional).
- [x] user-api: `RoutineService` — `@Transactional(readOnly=true)` 목록/단건 조회(소유권 guard, 필터) / `@Transactional` 등록(categoryId 있으면 소유권 검증 후 연결, status=ACTIVE) / 수정(소유권 guard, null 미변경, categoryId 변경 시 재검증) / 삭제(soft delete). 모든 단건은 `findByIdAndUserIdAndDeletedAtIsNull` 후 없으면 `ROUTINE_NOT_FOUND`.
- [x] user-api: `RoutineController` — `/api/v1/routines` GET(필터 query)/POST, `/api/v1/routines/{id}` GET/PUT/DELETE. `@CurrentUser AuthUser`로 userId 주입(CategoryController 패턴). Controller 얇게, Swagger `@Operation` 존대.

### test
- [x] test: `RoutineServiceIntegrationTest`(@DataJpaTest + 실제 Flyway 스키마) — 등록 시 status ACTIVE 자동 / categoryId 미지정 미분류 / 본인 카테고리 연결 / **타인 카테고리 지정 시 거부** / 타인 루틴 접근 시 ROUTINE_NOT_FOUND(소유권 guard) / 수정 시 null 필드 미변경·categoryId 변경 / 삭제 시 deleted_at 설정·목록 제외 / 목록 필터(categoryId·status).
- [x] test: `RoutineControllerTest`(@WebMvcTest) — 요청/응답 계약(items 배열, 응답 필드), title 누락·authType 누락 시 400 + VALIDATION_FAILED + fieldErrors, POST 201, 없는 루틴 GET/PUT 404 + ROUTINE_NOT_FOUND, DELETE 204.

## 위험영역 메모
재화·완료 직접 변경은 없으나 소유권 guard(인증/인가)가 핵심. 타인 루틴·타인 카테고리 접근 차단 테스트 필수. `/run-plan` 종료 후 `reviewer` 검토 권장(인증/인가 관점), PR 리뷰 권장.

## 연계
- 완료 체크/취소(POST·DELETE `/routines/{id}/logs`)는 [routine-logs.md](routine-logs.md)에서 다룸. 이 plan은 루틴 자체의 CRUD만.
