# Dev Credentials

이 문서는 Rougether dev 환경 접속 정보의 위치만 기록합니다.
비밀번호 원문은 저장소, 이슈, PR, 채팅에 남기지 않고 AWS SSM SecureString에서 조회합니다.

## AWS

- Region: `ap-northeast-2`
- Active account: **Innovation Sandbox 계정(2026-08-07 이전, #272)** — profile `rougether-isb`(IAM Identity Center SSO)
- 옛 계정(478572912668, CloudFront `d12w5t2ftuhgj8`)의 스택은 더 이상 응답하지 않습니다. 로컬 `deploy/terraform/ec2/terraform.tfstate`가
  옛 계정 것일 수 있으므로 `terraform output`으로 얻은 instance id·RDS endpoint를 그대로 믿지 말고, 아래처럼 **로그인한 profile에서 직접 조회**합니다.
- 현재 API base: `https://dkfiwkal2ezg9.cloudfront.net`(모바일 `shared-endpoints.json`과 동일)

팀원이 아래 값을 조회하려면 AWS IAM 권한이 필요합니다.
AWS IAM Identity Center 환경에서는 먼저 로그인하고 같은 profile을 모든 명령에 전달합니다.
SSO 토큰은 만료되면 `aws sso login`을 다시 실행합니다(브라우저 승인, 비밀번호는 각자 입력).

```bash
export AWS_PROFILE=rougether-isb
aws sso login --profile "$AWS_PROFILE"
aws sts get-caller-identity
```

## Admin

- Admin URL: 새 계정 state가 있는 checkout에서 `terraform -chdir=deploy/terraform/ec2 output -raw admin_url`(영구 HTTPS, #274).
  state가 없으면 CloudFront 배포 목록에서 comment가 `rougether-dev admin-api HTTPS entrypoint`인 배포의 도메인을 씁니다:
  `aws cloudfront list-distributions --query "DistributionList.Items[?contains(Comment, 'admin-api')].DomainName" --output text`
- Username: `admin`
- Password SSM parameter: `/rougether-dev/admin/seed-password`

Admin URL은 별도 CloudFront HTTPS 배포를 사용하며 EC2 `:8081` 직접 접속은 차단됩니다.
CloudFront 장애 시에는 별도 터미널에서 암호화된 SSM 포트 포워딩을 비상 접근 경로로 사용합니다
(`terraform output -raw admin_tunnel_command`, 또는 아래 EC2 instance id로 `aws ssm start-session --document-name AWS-StartPortForwardingSession --parameters '{"portNumber":["8081"],"localPortNumber":["18081"]}'`).

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
- RDS endpoint: 로그인한 profile에서 조회 — `aws rds describe-db-instances --db-instance-identifier rougether-dev-mysql --query 'DBInstances[0].Endpoint.Address' --output text`
- Current EC2 instance id: `aws ec2 describe-instances --filters Name=tag:Name,Values=rougether-dev-app Name=instance-state-name,Values=running --query 'Reservations[0].Instances[0].InstanceId' --output text`
- `deploy/scripts/db-tunnel.sh`는 위 두 조회를 내부에서 수행하므로 Terraform state 없이도 동작합니다.

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

CLI로 붙을 때: Homebrew `mysql` 9.x 클라이언트는 `mysql_native_password` 플러그인이 제거돼 `ERROR 2059`로 실패합니다.
`brew install mysql-client@8.4`의 `mysql`을 쓰거나, Python `pymysql`처럼 native password를 지원하는 드라이버로 접속합니다.

## Notes

- `localhost:3308`은 SSM 터널이 살아있는 동안만 동작합니다.
- 터널이 끊기면 `./deploy/scripts/db-tunnel.sh 3308`을 다시 실행합니다.
- EC2 instance id는 재생성되면 바뀔 수 있습니다. `db-tunnel.sh`는 매번 태그로 다시 찾으므로 별도 갱신이 필요 없습니다.
- 계정 이전 절차와 백업 원칙은 [aws-account-migration.md](aws-account-migration.md)를 따릅니다. Sandbox 리스가 만료되면 모든 리소스가 삭제되므로 DB dump·S3·Terraform state 백업을 계정 밖에 유지합니다.
- `allowed_admin_api_cidrs`는 항상 빈 목록이어야 합니다. admin-api public ingress를 열지 말고 SSM 터널을 사용합니다.
