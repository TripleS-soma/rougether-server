---
name: add-swagger
description: user-api 컨트롤러/DTO에 Swagger(OpenAPI) 애노테이션을 프로젝트 컨벤션에 맞춰 붙인다. "swagger 붙여줘", "OpenAPI 문서화", "@Operation/@Schema 추가" 등을 요청할 때 사용한다.
metadata:
  version: 0.1.0
---

# Add Swagger Annotations

Rougether `user-api` 모듈의 컨트롤러와 DTO에 Swagger(springdoc-openapi) 애노테이션을 일관된 컨벤션으로 붙인다.

## 적용 대상

대상 도메인(예: `category`, `auth`, `member`)을 받으면 해당 패키지의 컨트롤러와 그 컨트롤러가 쓰는 요청/응답 DTO에 애노테이션을 붙인다.

- 컨트롤러: `user-api/src/main/java/com/triples/rougether/userapi/<domain>/controller/*Controller.java`
- DTO: `.../<domain>/dto/*.java`

## 컨벤션 (반드시 지킬 것)

### 컨트롤러

- 클래스에 `@Tag(name = "<PascalCase 도메인>", description = "<도메인> 관련 API")`
  - description은 **포괄적으로** 쓴다. 나중에 엔드포인트가 추가돼도 맞도록 "~ 관련 API" 형태. 특정 동작(CRUD, 발급 등)을 나열하지 않는다.
- 각 핸들러 메서드에 `@Operation(summary = "...", description = "...")`
  - `summary`: 짧은 한국어 동작 이름 (예: "카테고리 생성")
  - `description`: 한 문장 설명. **존대 `~합니다.`** 로 끝낸다.
  - **에러/예외 동작은 description에 쓰지 않는다** (예: "미존재는 404" 같은 문구 금지).
- `@PathVariable`에는 `@Parameter(description = "...")`.
- 토큰 없이 호출하는 엔드포인트(로그인/토큰재발급/로그아웃 등)에는 `@SecurityRequirements`(빈 값)를 붙여 전역 JWT 자물쇠를 뗀다.

### DTO (record)

- 각 필드(record component) 위에 `@Schema(description = "...", example = "...")`.
  - `description`이 명사구("카테고리 이름")면 종결어미 없이 그대로.
  - `example`은 가능하면 채운다. 토큰/시각 등 예시가 무의미한 필드는 `example` 생략하고 `description`만.
- 필드가 1개뿐인 **단순 래퍼 DTO**(예: `CategoryListResponse(List<...> items)`)는 `@Schema`를 생략한다.

### 하지 않는 것

- `@ApiResponse`는 붙이지 않는다.
- 메서드별 `security` 재선언은 하지 않는다 (JWT는 `OpenApiConfig` 전역 설정).

## 작업 절차

1. 대상 도메인의 컨트롤러/DTO 파일을 모두 읽는다.
2. 위 컨벤션대로 애노테이션을 추가한다. import도 함께 추가.
   - `io.swagger.v3.oas.annotations.{Operation, Parameter}`
   - `io.swagger.v3.oas.annotations.tags.Tag`
   - `io.swagger.v3.oas.annotations.media.Schema`
   - `io.swagger.v3.oas.annotations.security.SecurityRequirements` (필요 시)
3. 검증: `./gradlew :user-api:compileJava -q`
4. 기존 코드 주석/로직은 건드리지 않는다. 애노테이션만 추가.

## 참고 (이미 적용된 예시)

`category`, `auth`, `member` 도메인이 이 컨벤션으로 적용돼 있다. 새 도메인 작업 시 이 파일들을 참고한다.
