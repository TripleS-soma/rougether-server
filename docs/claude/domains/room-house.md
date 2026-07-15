# 방 / 공동집 도메인 (서버 구현 노트)

도메인 계약(기능·API·데이터)의 **정본은 spec repo**에 있습니다. 이 문서와 어긋나면 spec이 우선입니다.

- 개인 방: [rougether-spec](https://github.com/TripleS-soma/rougether-spec) repo의 `domains/room/`
- 공동 집: 같은 repo `domains/house/`
- 전체 ERD: 같은 repo `erd.md` · API 공통 규약: 같은 repo `api.md`

이 문서는 이 서버 repo에서의 **구현 노트**(Spring 패키지 구조, 트랜잭션 경계, 서버 특이사항)만 둡니다.

## 구현 노트

- `GET /api/v1/houses/cover-images`는 게시 승인된 집 커버 key manifest를 key 오름차순으로 반환합니다.
- manifest는 `house.cover-images.keys` 설정으로 관리하며, S3 `house/`의 초안·중복 파일은 자동 노출하지 않습니다.
- 응답에는 전체 URL이나 S3 운영 메타데이터를 노출하지 않고 `coverImageKey`만 제공합니다.
