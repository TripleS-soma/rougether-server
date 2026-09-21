# 한국어·영어 및 개인 알림 시간대 구현

정본 계약: rougether-spec의 `global-localization.md`.

- `PATCH /api/v1/me`로 `language`(ko/en), `timeZone`(IANA)을 부분 저장한다. 기본값은 ko/Asia/Seoul이며 기존 PUT 프로필 API는 유지한다.
- 카탈로그·조정 추천 응답은 `Accept-Language`를 사용한다. 앱은 로그인/언어 변경/기기 시간대 변경 때 계정 설정도 동기화해야 한다. 요청 헤더만으로 비동기 알림 언어를 바꾸지 않는다.
- 개인 루틴·투두 예약, 저녁 미완료, 복귀 알림 허용 시간에 회원 시간대를 적용한다. 공동 미션·출석·보상 및 기존 통계/회고 날짜는 KST를 유지한다.
- 카탈로그 번역은 `name_translations` JSON에 저장한다. 관리자 `GET/PUT /admin/catalog/{kind}/{id}/translations`로 운영 번역을 관리한다. kind는 items/themes/characters/goals/gacha다.
- V78은 기존 ID/key/원문을 유지하는 추가 컬럼 migration이다. 알려진 기본 캐릭터·목표·뽑기 코드의 영어 이름을 초기화한다. 운영 중 추가된 테마·가구 번역과 문구 검수는 별도 데이터 작업이다.
- 언어별 알림은 생성 시점 스냅숏을 보존한다. 주간 회고도 과거 결과를 재생성하지 않는다. 조정 추천은 새 데이터부터 코드·매개변수를 저장하고, 기존 데이터는 원문으로 폴백한다.

배포 시 V78을 적용한 batch를 먼저 반영하고, 언어·시간대 저장 API를 제공하는 user-api를 반영한다. 구형 batch는 해외 시간대를 모르므로 신형 API와 혼재시키지 않는다. 관리자 번역 API는 admin-api 배포 후 사용한다.

검증은 `./gradlew compileJava test`, `git diff --check`를 사용한다. 단위·MockMvc·MySQL 통합 테스트로 migration·JSON 저장/재조회·수신자별 언어·해외 날짜/요일·DST 중복·기존 한국어 호환을 확인한다.
