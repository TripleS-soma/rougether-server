# 공개 SNS 피드 구현·운영

공유 계약은 [spec 피드 API](https://github.com/TripleS-soma/rougether-spec/blob/codex/sns-feed/domains/feed/api.md)와 기능 명세에 둡니다. 이 문서는 백엔드 구현 및 배포 의존성을 설명합니다.

## 구조

- `domain.feed`: 게시물·사진·좋아요·댓글 entity/repository, `V79__add_feed.sql` 신규 4개 테이블.
- `userapi.feed`: REST API, 활성 회원 guard, 조회/쓰기 서비스, 사진 변환·저장·정리.
- 조회는 작성자를 fetch join하고 사진·좋아요 수·댓글 수·내 좋아요를 페이지 단위로 일괄 읽습니다. 컬렉션 fetch join에 pagination을 걸지 않습니다.
- 쓰기는 `READ_COMMITTED`에서 행 잠금과 unique 제약을 함께 씁니다. 잠금 순서는 요청 사용자 → 기존 게시물 → 사진 ID 오름차순입니다. 사진 표시 순서는 요청 순서로 따로 저장합니다.
- 사용자 잠금은 탈퇴와 등록을 직렬화합니다. 게시물 잠금은 삭제와 좋아요·댓글 요청을 직렬화합니다. 재시도 UUID와 최초 요청 SHA-256은 수정 후에도 바꾸지 않습니다.
- 기존 루틴/집/보상/알림 서비스를 호출하거나 자동 공유하지 않습니다.

## 사진 수명

1. 실제 JPEG/PNG를 디코딩해 용량·치수 제한을 검증하고, EXIF 회전 보정 후 최대 긴 변 1,600px JPEG로 다시 인코딩합니다. 원본 metadata는 복사하지 않습니다. 회전 유틸은 가구 이미지 경로와 `common.image.PhotoOrientation`을 공유합니다.
2. 업로드 전에 비공개 key를 `ready=false`, 만료 24시간으로 DB에 커밋합니다. S3 I/O는 이 트랜잭션 밖에서 수행하고 전송 API 호출은 30초로 제한합니다.
3. 전송 후 별도 트랜잭션으로 회원·사진 상태를 다시 확인하고 `ready=true`로 바꿉니다. 전송 실패/프로세스 종료/중간 탈퇴에도 key가 남아 나중에 정리할 수 있습니다. 미완료 row를 즉시 삭제하지 않아 아직 진행 중인 전송과 충돌하지 않습니다.
4. 게시 시 사진을 잠가 소유권·완료·유효기간·미사용을 검증합니다. 이미지 조회는 공개 CDN 대신 JWT API를 통하고 `private, no-store`로 응답합니다.
5. `FeedCleanupJob`은 탈퇴자의 텍스트·반응을 정리하고, 미게시 만료 사진 및 삭제된 글/탈퇴자의 완료 사진을 ID cursor로 100개씩 처리합니다. 여러 인스턴스가 실행해도 이미지 잠금 안에서 상태를 재확인합니다. 저장소 삭제 실패 시 row를 유지해 다음 실행에서 재시도합니다.
6. 사진 삭제는 S3의 정확히 같은 key에 대한 모든 object version과 delete marker를 제거합니다. 공개 CDN 경로에는 사진을 넣지 않습니다.

삭제·탈퇴는 신규 요청의 노출을 즉시 차단합니다. 이미 전송된 응답까지 회수하지는 않습니다. 삭제 기록의 ID·작성자 ID·UUID·hash는 재시도 방지용으로 남으며 보존기간은 spec의 열린 질문입니다.

설정: `feed.cleanup.enabled` 기본 true, `feed.cleanup.delay-ms` 기본 300000. 최초 대기/실행 완료 후 대기에 같은 값을 씁니다. 정리 실패는 `피드 이미지 정리 실패 - imageId=...` 로그로 확인합니다. S3 실패가 누적되면 DB row와 실제 객체가 남으므로 로그·저장량을 점검해야 합니다.

## 배포 순서와 롤백

1. 최신 main의 Flyway 번호와 V79 충돌 여부를 다시 확인합니다. 이미 적용한 migration은 수정하지 않습니다.
2. `deploy/terraform/ec2/main.tf`의 EC2 앱 역할에 `private/feed/*` 읽기/쓰기/삭제/version 삭제 및 해당 prefix의 `ListBucketVersions` 권한을 적용합니다. Terraform 변경은 이 구현 작업에서 **운영 적용하지 않았습니다**. 공개 S3/CloudFront allowlist에는 이 prefix를 추가하지 않습니다.
3. 새 user-api의 Flyway migrate가 실행되는 단계에서 V79를 적용한 다음 새 domain 스키마를 validate하는 batch/worker를 갱신합니다. 배포 방식상 batch가 먼저 시작한다면 라우팅하지 않은 user-api 또는 별도 migration 단계가 선행해야 합니다.
4. 두 계정으로 업로드 → 게시 → 타 계정 이미지 조회 → 좋아요·댓글 → 삭제 후 404 → 정리 후 S3 version 소멸을 확인합니다. 이것은 실제 IAM/S3 검증이며 로컬 mock 저장소 테스트로 대체하지 않습니다.
5. V79는 새 테이블만 추가하므로 이전 앱으로 롤백해도 기존 테이블에는 영향이 없습니다. 롤백 중에는 피드 API와 정리 작업이 중단됩니다. 업로드된 객체/테이블을 임의로 삭제하지 않고 재배포 후 정리를 재개합니다.

## 검증

```bash
./gradlew compileJava test
git diff --check
terraform fmt -check deploy/terraform/ec2/main.tf
```

`FeedIntegrationTest`는 MySQL Testcontainers에서 실제 HTTP/JWT와 서비스 트랜잭션을 사용하고, S3 어댑터만 대체합니다. 등록부터 삭제까지의 흐름, 소유권, 재시도·동시 요청, 삭제 경합, 실제 회원탈퇴, 만료/취소/삭제 실패 재시도, 업로드 중 탈퇴, 봇·금칙어·입력 제한을 확인합니다. 사진 디코딩·축소·EXIF 제거와 S3 요청 scope/버전 삭제는 별도 테스트로 검증합니다.

프론트 UI, 운영 IAM 적용, 실 S3 smoke, 운영 배포, 신고·차단·운영자 숨김은 이 구현 완료 주장에 포함되지 않습니다.
