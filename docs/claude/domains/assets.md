# 에셋 / 이미지 / CDN (서버 구현 노트)

에셋 관련 계약의 **정본은 spec repo**에 분산되어 있습니다.

- 아이템/테마: [rougether-spec](https://github.com/TripleS-soma/rougether-spec) repo의 `domains/shop/`
- 방 배치: 같은 repo `domains/room/`
- 공통 규약(이미지 key 원칙): 같은 repo `api.md`

원칙: 전체 CDN URL이 아니라 `*_key`(`asset_key`, `cover_image_key`, `storage_key`)로 저장하고, base URL은 클라이언트/서버 설정에서 조합합니다.

이 문서는 이 서버 repo에서의 **구현 노트**(스토리지 연동, 업로드 흐름 등)만 둡니다.

## 구현 노트

- (작성 예정)
