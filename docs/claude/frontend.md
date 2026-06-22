# Frontend 연동 기준

이 저장소는 백엔드 서버이지만, API 응답은 프론트 구현이 바로 쓰기 좋은 형태를 기준으로 설계합니다.

## 응답 설계 원칙

- DB table 구조를 그대로 응답하지 않습니다.
- 화면 단위로 필요한 값을 묶어 반환합니다.
- 이미지 표시는 전체 URL보다 `objectKey`, `thumbnailKey`, `snapshotObjectKey`를 우선 사용합니다.
- frontend는 환경별 CDN base URL과 `objectKey`를 조합해 이미지를 로드할 수 있어야 합니다.
- nullable field는 이유를 문서화합니다. 예: snapshot 생성 전에는 `snapshotObjectKey`가 null일 수 있음.

## 방 화면

방 화면은 다음 정보가 필요합니다.

- 방 ID
- 방 소유자 ID
- 방 이름
- 배치된 slot 목록
- 각 slot의 asset key와 object key
- 마지막 수정 시각

방 slot 응답은 frontend가 바로 렌더링할 수 있도록 `slotKey`, `assetKey`, `objectKey`, `category`를 포함합니다.

## 공동집 화면

공동집 화면은 여러 사용자의 방 정보를 한 번에 봐야 하므로 응답이 무거워지기 쉽습니다.

MVP에서는 다음 방향을 우선합니다.

- 공동집 목록: 공동집 metadata 중심
- 공동집 방 preview: snapshot 또는 lightweight summary 중심
- 방 상세: 실제 slot placement 전체 로드

공동집 preview에서 모든 사용자의 모든 에셋 placement를 한 번에 불러오는 방식은 피합니다.

## 프론트와 맞춰야 할 질문

- 방 slot의 고정 개수와 이름
- wallpaper/floor가 slot인지 theme field인지
- 공동집 preview 카드에서 필요한 최소 정보
- snapshot 이미지 생성 시점
- 에셋 thumbnail 크기와 원본 크기
- API error 응답 형태
