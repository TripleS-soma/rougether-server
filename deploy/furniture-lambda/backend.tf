terraform {
  # backend 설정은 배포 환경에서 -backend-config로 전달한다. DB 암호가 포함된
  # state는 암호화·버전 관리·공개 차단한 S3와 DynamoDB 잠금으로 관리한다.
  backend "s3" {}
}
