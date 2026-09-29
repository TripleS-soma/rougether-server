# app EC2 메모리 모니터링

app EC2(`rougether-dev-app`)의 메모리 추세를 CloudWatch 지표로 모으고, 임계치에 닿으면 운영 Webex로 알립니다(#419). OOM kill·재시작 같은 이벤트는 #418의 호스트 감시가 따로 알립니다. 이 스택은 추세와 임계치를 맡습니다.

메인 스택(`../ec2`)의 로컬 state는 옛 계정 것이라 이 스택은 state를 분리합니다. `ai-ec2`와 같이 S3 backend를 `-backend-config`로 주입합니다. 기존 EC2 역할은 참조만 하고 수정하지 않습니다. 관리형 정책 연결 하나만 이 스택이 소유합니다.

## 구성

- IAM
  - 기존 역할 `rougether-dev-ec2-role`에 인라인 정책 `rougether-dev-cloudwatch-metrics`를 붙입니다. `cloudwatch:PutMetricData` 하나만 허용하고, 조건 `cloudwatch:namespace = Rougether/Dev`로 네임스페이스를 좁힙니다. PutMetricData는 리소스 단위 권한이 없어 Resource는 `*`입니다.
  - 관리형 `CloudWatchAgentServerPolicy`는 쓰지 않습니다. logs, `ec2:DescribeTags`/`DescribeVolumes`, ssm까지 열기 때문입니다. 에이전트 설정에는 logs 섹션, `append_dimensions`, EC2 태그, 디스크 지표가 없어 PutMetricData 외 API를 부르지 않습니다(인스턴스 ID·리전은 IMDS에서 읽음). 설정에 그런 항목을 넣으면 정책도 같이 넓혀야 합니다.
  - 메인 스택의 `aws_iam_role.ec2`는 인라인 정책을 독점 관리하지 않으므로, 메인 스택을 apply해도 이 정책은 지워지지 않습니다.
- 알림 경로: CloudWatch 알람 → SNS 토픽 `rougether-dev-ops-alarms` → Lambda `rougether-dev-alarm-webex`(python3.14, arm64) → 운영 Webex room
  - Lambda는 `/rougether-dev/alerts/webex-bot-token`(SecureString)과 `/rougether-dev/alerts/webex-room-id`(String)만 `ssm:GetParameter`로 읽습니다. 두 이름은 `docker-publish.yml`의 `WEBEX_*_PARAMETER`와 같습니다.
  - 토큰은 AWS 관리형 키 `alias/aws/ssm`으로 암호화돼 있습니다. 이 키 정책이 같은 계정 주체의 SSM 경유 복호화를 허용하므로 `kms:Decrypt`는 주지 않습니다. 고객 관리형 키로 바꾸면 추가해야 합니다.
  - 알람 이름, 상태 전이, 사유, 시각(KST), 지표·차원을 markdown으로 보냅니다. 값 안의 `<@...>` 멘션과 markdown 강조 문자는 무력화합니다. 토큰·room은 로그와 예외 메시지에 남기지 않습니다.
  - 전송이 실패하면 예외를 올려 Lambda 비동기 재시도(최대 2회)에 맡깁니다. Webex가 401/403을 돌려주면 토큰 캐시를 비워 재시도가 SSM의 새 값을 읽게 합니다. 로그 그룹 보존 기간은 30일입니다.
  - 재시도까지 실패한 이벤트는 on-failure destination인 SQS DLQ `rougether-dev-alarm-webex-dlq`(보존 14일, SSE-SQS)로 갑니다.
  - SNS 토픽은 암호화하지 않습니다. 알람 데이터는 비밀이 아니고, CloudWatch 알람은 `aws/sns` 관리형 키로 암호화된 토픽에 게시할 수 없습니다.
- 지표(네임스페이스 `Rougether/Dev`, 60초 주기)
  - `mem_used_percent`, `swap_used_percent`: CloudWatch Agent가 보냅니다. `omit_hostname`으로 차원 없이 보내므로 인스턴스를 교체해도 알람이 그대로 맞습니다.
  - `container_memory_working_set`(Bytes, 차원 `Service=user-api|admin-api|batch`): 배포 스크립트가 설치하는 1분 timer(`rougether-memory-metrics`)가 보냅니다. 값은 컨테이너 cgroup의 `memory.current - inactive_file`입니다. blue/green 슬롯이 둘 다 떠 있으면 큰 값을 보냅니다.
  - 서비스별 값을 procstat으로 모으지 않는 이유가 있습니다. 세 이미지가 모두 같은 `java -jar /app/app.jar`로 떠서 procstat의 `pattern`·`exe`로는 서비스를 가를 수 없습니다. 또 `docker --memory` 상한이 거는 값은 프로세스 RSS가 아니라 cgroup 사용량입니다.
- 알람(모두 ALARM과 OK를 SNS로 보냅니다)
  - `rougether-dev-host-memory-high`: `mem_used_percent` 평균 ≥ 90%가 1분 구간 5개 연속입니다. 차원은 없습니다.
  - `rougether-dev-user-api-memory-high`, `rougether-dev-admin-api-memory-high`, `rougether-dev-batch-memory-high`: `container_memory_working_set` 최댓값 ≥ 상한의 90%가 1분 구간 5개 연속입니다. 상한 기본값은 #418과 같습니다(user-api 1280MiB, admin-api·batch 768MiB). 지표가 없으면(컨테이너 중단) OK로 봅니다. 중단 자체는 #418 감시가 알립니다.
  - `rougether-dev-host-metrics-missing`: `mem_used_percent` 표본이 10분(5분 구간 2개) 동안 없으면 ALARM입니다(`treat_missing_data=breaching`). 에이전트 중단, 권한 오류, 인스턴스 정지를 잡습니다.
  - `rougether-dev-service-metrics-missing`: `container_memory_working_set`(`Service=user-api`) 표본이 10분 동안 없으면 ALARM입니다. 서비스 알람은 지표가 없으면 OK로 보므로, 수집기가 멈추는 것은 이 heartbeat가 잡습니다. user-api는 항상 떠 있어야 하는 서비스라 기준으로 삼았습니다.
- 알림 경로 실패 알람(fallback 토픽 `rougether-dev-ops-alarms-fallback`으로 보냄)
  - `rougether-dev-alarm-webex-errors`: 전달 Lambda `Errors` 합 ≥ 1(1분)
  - `rougether-dev-alarm-webex-dlq-not-empty`: DLQ `ApproximateNumberOfMessagesVisible` 최댓값 ≥ 1(5분)
  - 이 두 알람을 메인 토픽으로 보내면 고장 난 Lambda를 다시 거쳐 실패가 순환합니다. 그래서 Lambda를 거치지 않는 별도 토픽으로 보냅니다.
  - fallback 토픽의 이메일 구독은 `fallback_email`을 줄 때만 만듭니다. 기본값은 빈 값이라 구독이 없고, 이때는 콘솔 알람 상태와 대시보드로만 보입니다. 이메일을 주면 수신자가 AWS의 확인 메일을 승인해야 전달이 시작됩니다.
  - DLQ에 쌓인 메시지는 `aws sqs receive-message --queue-url "$(terraform output -raw forwarder_dlq_url)"`로 원문 알람을 확인합니다. 원인을 고친 뒤 삭제합니다.
- 대시보드 `rougether-dev-memory`: 호스트 메모리·스왑 사용률(90% 선), 알람 상태, 서비스별 working set(MiB)과 상한·알람 수평선입니다.

컨테이너 상한을 `ROUGETHER_*_MEMORY_LIMIT`로 바꾸면 `service_memory_limits_mib`도 같이 바꿔야 합니다. 네임스페이스·지표 이름·차원과 상한 기본값이 배포 스크립트와 맞는지는 `.github/scripts/test-deploy-ec2-with-rollback.sh`가 확인합니다.

## state 버킷 준비(최초 1회)

현재 계정(776158585524)에는 terraform state 버킷이 없습니다. 아래처럼 암호화·버전 관리·공개 차단이 걸린 버킷과 잠금 테이블을 만듭니다. 이미 있으면 건너뜁니다. 다른 스택도 같은 버킷을 key만 달리해 쓸 수 있습니다.

```bash
export AWS_PROFILE=rougether-isb AWS_REGION=ap-northeast-2
STATE_BUCKET=rougether-terraform-state-776158585524
STATE_LOCK_TABLE=rougether-terraform-locks

aws s3api create-bucket --bucket "$STATE_BUCKET" \
  --create-bucket-configuration LocationConstraint=ap-northeast-2
aws s3api put-bucket-versioning --bucket "$STATE_BUCKET" \
  --versioning-configuration Status=Enabled
aws s3api put-bucket-encryption --bucket "$STATE_BUCKET" \
  --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"},"BucketKeyEnabled":true}]}'
aws s3api put-public-access-block --bucket "$STATE_BUCKET" \
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-ownership-controls --bucket "$STATE_BUCKET" \
  --ownership-controls 'Rules=[{ObjectOwnership=BucketOwnerEnforced}]'

aws dynamodb create-table --table-name "$STATE_LOCK_TABLE" \
  --attribute-definitions AttributeName=LockID,AttributeType=S \
  --key-schema AttributeName=LockID,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST
```

Terraform 1.10 이상이면 DynamoDB 대신 `-backend-config=use_lockfile=true`로 S3 잠금을 쓸 수 있습니다. 로컬 기본 설치본(1.5.7)은 DynamoDB 잠금을 씁니다.

## 적용 순서

1. **배포 먼저**: 이 변경이 들어간 배포(`DEPLOY_MODE`가 `hold`가 아닐 때)가 CloudWatch Agent와 `rougether-memory-metrics` timer를 설치합니다. 정책이 붙기 전에는 지표 전송이 권한 오류로 실패만 하고, 배포는 계속됩니다. 배포 모드가 `hold`이면 아무것도 설치되지 않습니다.
   apply 전에 설치 여부를 확인합니다: `aws ssm send-command --instance-ids i-05dcf7064de948c4e --document-name AWS-RunShellScript --parameters 'commands=["systemctl is-active amazon-cloudwatch-agent rougether-memory-metrics.timer"]'` 결과가 두 줄 모두 `active`여야 합니다(`aws ssm get-command-invocation`으로 출력 확인).
2. **terraform apply**: 정책이 붙으면 몇 분 안에 지표가 들어옵니다. 배포 전에 apply하면 `host-metrics-missing`·`service-metrics-missing`이 곧바로 ALARM을 보내고, 배포 후 OK로 돌아옵니다.

```bash
cd deploy/terraform/monitoring
export AWS_PROFILE=rougether-isb AWS_REGION=ap-northeast-2
cp terraform.tfvars.example terraform.tfvars   # 상한·임계치를 바꿀 때만 수정
terraform init \
  -backend-config=bucket=rougether-terraform-state-776158585524 \
  -backend-config=key=monitoring/terraform.tfstate \
  -backend-config=region=ap-northeast-2 \
  -backend-config=encrypt=true \
  -backend-config=dynamodb_table=rougether-terraform-locks
terraform plan -out=monitoring.tfplan
terraform apply monitoring.tfplan
```

apply하면 기본값에서 20개 리소스가 생깁니다(`fallback_email`을 주면 이메일 구독까지 21개). Lambda zip은 `.build/`에 만들어집니다(git 제외). 적용 후 지표가 들어오는지 확인합니다.

```bash
aws cloudwatch list-metrics --namespace Rougether/Dev --output table
```

`mem_used_percent`, `swap_used_percent`(차원 없음)와 `container_memory_working_set`(Service 3개)이 보여야 합니다. 호스트에서는 `systemctl status amazon-cloudwatch-agent rougether-memory-metrics.timer`, `journalctl -u rougether-memory-metrics -n 20`으로 확인합니다.

## 알람 전달 시험

`set-alarm-state`로 상태를 강제로 바꾸면 Webex에 ALARM 메시지가 옵니다. 다음 평가(약 1분)에서 실제 상태로 돌아오며 OK 메시지가 옵니다.

```bash
aws cloudwatch set-alarm-state \
  --alarm-name rougether-dev-user-api-memory-high \
  --state-value ALARM \
  --state-reason "set-alarm-state 전달 시험 (#419)"

aws logs tail /aws/lambda/rougether-dev-alarm-webex --since 10m
```

메시지가 오지 않으면 Lambda 로그에서 `SSM 파라미터 ... 조회 실패`(권한·이름), `Webex 응답 HTTP 401/404`(토큰·room, 봇이 room 멤버인지)를 확인합니다.

## 검증

```bash
terraform fmt -check
terraform init -backend=false && terraform validate
python3 -m unittest discover -s lambda/tests
```

## 비용(월, 서울 리전 추정)

- 커스텀 지표 5개(에이전트 2 + 서비스 3): 약 $1.5. 계정의 무료 10개 안이면 0입니다.
- PutMetricData 호출(에이전트·수집기 각 분당 1회): 약 $0.9
- 알람 8개: $0.8. 무료 10개 중 계정에 이미 8개가 있어 실제 초과분은 약 $0.6입니다.
- 대시보드 1개: 계정당 3개까지 무료입니다.
- Lambda·SNS·SQS·로그: 무료 구간 안입니다.
