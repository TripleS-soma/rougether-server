# Dev Credentials

이 문서는 Rougether dev 환경 접속 정보의 위치만 기록합니다.
비밀번호 원문은 저장소, 이슈, PR, 채팅에 남기지 않고 AWS SSM SecureString에서 조회합니다.

## AWS

- Region: `ap-northeast-2`
- Active account/profile: 배포 전 `aws sts get-caller-identity`와 `terraform output`으로 확인

팀원이 아래 값을 조회하려면 AWS IAM 권한이 필요합니다.
AWS IAM Identity Center 환경에서는 먼저 로그인하고 같은 profile을 모든 명령에 전달합니다.

```bash
export AWS_PROFILE=rougether-isb
aws sso login --profile "$AWS_PROFILE"
aws sts get-caller-identity
```

## Admin

- Admin URL: `terraform -chdir=deploy/terraform/ec2 output -raw admin_url`
- Username: `admin`
- Password SSM parameter: `/rougether-dev/admin/seed-password`

Admin URL은 별도 CloudFront HTTPS 배포를 사용하며 EC2 `:8081` 직접 접속은 차단됩니다.
CloudFront 장애 시에는 별도 터미널에서 암호화된 SSM 포트 포워딩을 비상 접근 경로로 사용합니다.

```bash
$(terraform -chdir=deploy/terraform/ec2 output -raw admin_tunnel_command)
```

```bash
aws ssm get-parameter \
  --name /rougether-dev/admin/seed-password \
  --with-decryption \
  --query 'Parameter.Value' \
  --output text \
  --region ap-northeast-2
```

## RDS MySQL

기본 접속 경로는 SSM 포트 포워딩입니다 — `localhost`를 열고 IntelliJ/DataGrip에서 그 포트로 접속합니다.
예외로, 보안그룹에 등록된 특정 개인 IP는 RDS endpoint 직접 접속이 허용돼 있습니다
(`deploy/terraform/ec2` 변수 `db_direct_access_cidrs`, 값은 커밋하지 않고 로컬 `terraform.tfvars`로 관리 — IP가 바뀌면 tfvars 수정 후 `terraform apply`).

- Database: `rougether`
- Username: `rougether`
- Password SSM parameter: `/rougether-dev/db/password`
- RDS endpoint: `terraform -chdir=deploy/terraform/ec2 output -raw rds_endpoint`
- Current EC2 instance id: `terraform -chdir=deploy/terraform/ec2 output -raw ec2_instance_id`

DB password 조회:

```bash
aws ssm get-parameter \
  --name /rougether-dev/db/password \
  --with-decryption \
  --query 'Parameter.Value' \
  --output text \
  --region ap-northeast-2
```

로컬 포트 포워딩:

```bash
./deploy/scripts/db-tunnel.sh 3308
```

IntelliJ/DataGrip 설정:

- Host: `127.0.0.1`
- Port: `3308`
- User: `rougether`
- Password: SSM에서 조회한 `/rougether-dev/db/password`
- Database: `rougether`
- JDBC URL: `jdbc:mysql://127.0.0.1:3308/rougether`

## Notes

- `localhost:3308`은 SSM 터널이 살아있는 동안만 동작합니다.
- 터널이 끊기면 `./deploy/scripts/db-tunnel.sh 3308`을 다시 실행합니다.
- EC2 instance id는 재생성되면 바뀔 수 있습니다. 바뀌면 Terraform output 또는 AWS Console에서 `rougether-dev-app` 인스턴스를 확인합니다.
- `allowed_admin_api_cidrs`는 항상 빈 목록이어야 합니다. admin-api public ingress를 열지 말고 SSM 터널을 사용합니다.
