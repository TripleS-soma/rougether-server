# 비공개 채팅 사진 권한

기존 EC2 스택과 별도 state로 앱 실행 역할에 `private/chat/*`만 허용합니다. 인스턴스, DB, 버킷 공개 정책은 변경하지 않습니다. state는 다른 스택과 공유하거나 커밋하지 않습니다.

1. AWS profile, STS 계정, 실제 앱 EC2 instance profile을 확인합니다.
2. `terraform init` 후 `terraform plan -var region=... -var account_id=... -var role_name=... -var bucket_name=... -out=chat.tfplan`을 실행합니다.
3. plan이 `aws_iam_role_policy.chat_media` 하나 생성/수정만 포함하고 삭제가 없는지 확인합니다. 검토한 saved plan만 적용합니다.
4. IAM simulation으로 prefix 내부 Put/Get/Delete/DeleteVersion 및 prefix 제한 ListBucketVersions를 확인합니다.
5. 서버 blue-green 배포 후 인증 API로 테스트 사진 업로드·조회·삭제를 확인합니다. SSM/SSH 수동 변경은 사용하지 않습니다.
