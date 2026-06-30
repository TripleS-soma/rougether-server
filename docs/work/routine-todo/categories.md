# 카테고리(categories) API 구현 계획
출처: ../rougether-spec/domains/routine-todo/ · 생성: 2026-06-27

내 카테고리 목록 조회 / 등록 / 수정 / 삭제(soft). 모든 엔드포인트는 JWT 인증 + `user_id` 소유권 guard 적용. 응답 envelope 없음, 목록은 `items` 배열.

## 결정값 (확정)
- **visibility 포함**. 허용값 `PRIVATE`(나만 보기) / `HOUSE`(집 구성원 공개), 기본 `PRIVATE`. 기존 `PrivacyScope` enum 재사용. → POST/PUT 요청·GET 응답에 `visibility` 포함. (features.md "공개 범위는 카테고리 단위" 설계 반영)
- **sortOrder 기본값**: POST에서 미지정 시 해당 유저 `max(sort_order)+1` (목록 맨 뒤). 첫 카테고리면 0.
- **삭제(soft delete)**: `deleted_at` 설정 + 소속 `routines.category_id` / `todos.category_id`를 NULL(미분류)로. **애플리케이션 레이어 벌크 UPDATE를 한 트랜잭션**으로 처리(DB cascade 미사용 — 명시적·테스트 가능). FK는 이미 nullable.
- colorHex/iconKey는 nullable, 별도 형식 강제 검증 없음(길이 제약만: colorHex ≤ 20, iconKey ≤ 100, name ≤ 100). icon_key는 키만 저장·전달.

## 미해결 (구현 전 확정 필요)
- 없음

## 사전 확인 (스키마는 이미 존재)
- `categories` 테이블·`fk_category_user`·`fk_routine_category`·`fk_todo_category`는 V1에 존재. **신규 migration 불필요.**
- `Category` 엔티티·`CategoryRepository`는 이미 존재 → 확장만 함.

## 스텝

### domain 모듈
- [x] domain: `Category` 엔티티 확장 — `visibility` 필드 타입을 `String` → `PrivacyScope`(@Enumerated(EnumType.STRING))로 변경. 생성 정적 팩토리(`create(user, name, colorHex, iconKey, sortOrder, visibility)`)와 수정 메서드(`update(...)`, `softDelete(Instant)`) 추가. visibility 기본 PRIVATE는 Service에서 주입.
- [x] domain: `CategoryRepository` 확장 — `findByIdAndUserIdAndDeletedAtIsNull(id, userId)`(소유권 guard 조회), `findMaxSortOrderByUserId(userId)`(sortOrder max+1 계산용, `@Query("select max(c.sortOrder) ...")` → nullable). 기존 `findByUserIdAndDeletedAtIsNull`는 목록용으로 sortOrder 정렬 추가(`findByUserIdAndDeletedAtIsNullOrderBySortOrderAsc`).
- [x] domain: `RoutineRepository`·`TodoRepository`에 미분류 처리 벌크 UPDATE 추가 — `@Modifying @Query("update Routine r set r.category = null where r.category.id = :categoryId")` (Todo 동일). 삭제 트랜잭션에서 사용. (위험)
- [x] migration: **불필요** (categories 테이블·FK 모두 V1 존재, visibility 컬럼도 VARCHAR(30)로 존재)

### user-api 모듈
- [x] user-api: `CategoryErrorCode` — `CATEGORY_NOT_FOUND`("CATEGORY_NOT_FOUND", 404). 미존재·타인 소유 모두 404로 통일(존재 노출 회피). FORBIDDEN은 불필요 판단해 미추가.
- [x] user-api: DTO — `CategoryResponse`(id, name, colorHex, iconKey, sortOrder, visibility), `CategoryListResponse`(items 배열), `CategoryCreateRequest`(name @NotBlank @Size(max=100), colorHex? @Size(max=20), iconKey? @Size(max=100), sortOrder? @Min(0), visibility? 기본 PRIVATE), `CategoryUpdateRequest`(전부 optional).
- [x] user-api: `CategoryService` — `@Transactional(readOnly=true)` 목록 조회 / `@Transactional` 등록(sortOrder 미지정 시 max+1, visibility 미지정 시 PRIVATE) / 수정(소유권 guard, null 필드는 미변경) / 삭제(soft delete + routines·todos categoryId NULL 벌크 UPDATE 한 트랜잭션). 모든 단건 작업은 `findByIdAndUserIdAndDeletedAtIsNull`로 소유권 확인 후 없으면 `CATEGORY_NOT_FOUND`. (위험: 삭제 트랜잭션·정합성)
- [x] user-api: `CategoryController` — `/api/v1/categories` GET/POST, `/api/v1/categories/{id}` PUT/DELETE. `@CurrentUser AuthUser`로 userId 주입(MemberController 패턴). Controller는 얇게.

### test
- [x] test: `CategoryServiceIntegrationTest`(@DataJpaTest + 실제 Flyway 스키마) — 등록 시 sortOrder max+1 자동 부여 / 첫 카테고리 0 / visibility 기본 PRIVATE / 명시값 보존 / 타인 카테고리 접근 시 CATEGORY_NOT_FOUND(소유권 guard) / 수정 시 null·공백 name 미변경 / **삭제 시 deleted_at 설정 + 소속 routines·todos category_id NULL로 전이·목록 제외 정합성**. (위험)
- [x] test: `CategoryDeleteRollbackTest`(@SpringBootTest + @MockitoSpyBean) — todo 미분류 단계 실패 시 soft delete·routine 미분류가 **전부 롤백**되는지(트랜잭션 경계 실증). (위험)
- [x] test: `CategoryControllerTest` — `@WebMvcTest` 요청/응답 계약(items 배열, 응답 필드), name 누락 시 400 + VALIDATION_FAILED + fieldErrors, PUT 200, 없는 카테고리 PUT 404 + CATEGORY_NOT_FOUND, 삭제 204.

## 검증 보강 (verifier 권고 반영)
- 롤백 테스트 추가(`CategoryDeleteRollbackTest`) — 위험영역 메모의 "롤백 테스트 필수" 충족.
- HTTP 404 계약·PUT 라우팅 테스트 추가.
- `CategoryUpdateRequest.name` 공백 입력 시 기존 이름 덮어쓰기 방지(`Category.update` blank guard) + 테스트.

## 위험영역 메모
삭제 흐름은 categories(soft delete) + routines + todos 3개 테이블을 함께 변경 → `@Transactional` 필수, 롤백·정합성 테스트 필수. `/run-plan` 종료 후 `reviewer` 서브에이전트 검토 + PR 리뷰 필수 표시.

## 후속 고려사항 (비차단, 결정 필요 시 별도 처리)
- 삭제 시 `clearCategory` 벌크 update가 미분류된 routines/todos의 `updated_at`(@LastModifiedDate)을 갱신하지 않음. 프론트가 `updated_at` 델타로 동기화하는 정책이면 이 변경이 누락될 수 있음 → 동기화 정책 확정 후 필요하면 `updated_at`도 함께 set 하거나 엔티티 로드-수정 방식으로 전환. 스펙에 정의가 없어 이번 범위에서는 미반영.
