# Dev Credentials

이 문서는 Rougether dev 환경 접속 정보의 위치만 기록합니다.
비밀번호 원문은 저장소, 이슈, PR, 채팅에 남기지 않고 AWS SSM SecureString에서 조회합니다.

## AWS

- Region: `ap-northeast-2`
- AWS account: `478572912668`

팀원이 아래 값을 조회하려면 AWS IAM 권한이 필요합니다.

## Admin

- Admin URL: `http://43.203.209.107:8081/`
- Username: `admin`
- Password SSM parameter: `/rougether-dev/admin/seed-password`

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
- RDS endpoint: `rougether-dev-mysql.cvkuqa4iyrq2.ap-northeast-2.rds.amazonaws.com:3306`
- Current EC2 instance id: `i-08b3fb7120a871cc4`

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
aws ssm start-session \
  --target i-08b3fb7120a871cc4 \
  --region ap-northeast-2 \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters '{"host":["rougether-dev-mysql.cvkuqa4iyrq2.ap-northeast-2.rds.amazonaws.com"],"portNumber":["3306"],"localPortNumber":["3308"]}'
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
- 터널이 끊기면 다시 `aws ssm start-session ...` 명령을 실행합니다.
- EC2 instance id는 재생성되면 바뀔 수 있습니다. 바뀌면 Terraform output 또는 AWS Console에서 `rougether-dev-app` 인스턴스를 확인합니다.
