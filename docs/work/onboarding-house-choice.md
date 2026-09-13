# 온보딩 집 선택 API

2026-09-13 사용자 확인 정책을 반영했습니다. 가입 때 지급하는 개인집은 유지하고, 선택 결과에 따라 시작할 집을 반환합니다.

| 사용자 선택 | 처리 | result |
| --- | --- | --- |
| 좋아요 + 매칭 성공 | 자동 입주를 허용한 공개 집에 즉시 합류. 기존 개인집 유지 | `JOINED` |
| 좋아요 + 매칭 대상 없음 | 개인집을 공개·자동 입주 허용으로 설정. 다음 신규 사용자의 합류 대상이 됨 | `NO_MATCH` |
| 괜찮아요 | 비공개·자동 입주 비허용 개인집으로 시작 | `PERSONAL` |

## 프론트 연결

`PUT /api/v1/onboarding/house` (JWT 필수)

```json
{"choice":"AUTO_JOIN"}
```

`괜찮아요`는 `{"choice":"PERSONAL"}`을 보냅니다. 응답 예시:

```json
{"completed":true,"choice":"AUTO_JOIN","result":"NO_MATCH","houseId":42,"membershipId":73}
```

응답의 `houseId`를 시작할 집으로 사용합니다. `NO_MATCH`이면 합류 가능한 집이 없어 공개 집에서 시작한다는 안내를 표시합니다. `GET /api/v1/onboarding/house`로 선택 처리 여부를 복원할 수 있습니다. 미선택은 `completed=false`이고 나머지 값은 null입니다.

같은 선택 재시도는 최초 결과를 반환합니다. 완료 후 다른 선택은 409 `ONBOARDING_HOUSE_ALREADY_SELECTED`이며, 이후 집 변경은 일반 집 API를 사용합니다. 탈퇴·해체 이후에도 이 API가 재가입시키지 않습니다. 기존 온보딩의 목표·캐릭터 기반 `completed`는 유지합니다.

## 방장 설정

`GET /api/v1/houses/{houseId}/auto-join`과 `PUT /api/v1/houses/{houseId}/auto-join`은 활성 소유자 전용입니다.

```json
{"enabled":true}
```

공개 상태이면서 이 설정이 켜진 집만 온보딩 매칭 대상입니다. 기존 집의 설정 기본값은 false입니다. 공개 여부는 기존 집 설정 API를 사용합니다. 이 설정은 온보딩 자동 입주에 적용하며, 기존 탐색 입주 신청·초대코드 API 계약은 유지합니다.

## 저장과 정합성

- Flyway `V76__add_onboarding_house_selection.sql`: 집의 자동 입주 허용 플래그와 사용자당 1개 선택 결과 테이블을 추가합니다.
- 후보는 집 ID 오름차순으로 조회합니다. 미삭제 공개 집, 자동 입주 허용, 실사용자 존재, 정원 여유가 조건입니다. 봇/탈퇴한 소유자, 본인 소유, 과거 멤버십·입주 신청 이력이 있는 집은 제외합니다.
- 집 락 이후 후보 조건을 재검증하고 사용자 락으로 중복 처리를 막습니다. 가입·봇 자리 양보·정원 변경·입주 알림·선택 결과는 함께 커밋하거나 롤백합니다.
- 개인집 재사용은 본인 ACTIVE OWNER·비공개·실사용자 1명인 집 중 ID가 가장 작은 집입니다. 재사용할 집이 없으면 기본 집을 생성합니다.
- 집 설정 변경도 같은 집 락을 사용합니다. 다른 집 필드의 오래된 엔티티 갱신이 자동 입주 설정을 덮지 않도록 변경 필드만 UPDATE합니다.

정본 계약은 별도 `rougether-spec` 작업 공간의 `domains/member/api.md`, `domains/member/features.md`, `domains/house/api.md`, `domains/house/features.md`, `erd.md`에 함께 반영했습니다.

## 로컬 검증

- `./gradlew test` 통과: 총 2239개, 실패 0개, 오류 0개, 건너뜀 7개.
- 신규 기능 통합 테스트 18개 통과: MySQL 8.4 + Flyway, 실제 인증 필터와 HTTP 계약, 동시 중복 요청·정원·개인집 생성, 매칭 실패 후 다음 사용자 합류, 봇 자리 양보, 알림 저장 실패 롤백, 설정 덮어쓰기 방지.
- 서버·spec `git diff --check` 통과.
- 프론트 연결과 배포는 별도 작업입니다.
