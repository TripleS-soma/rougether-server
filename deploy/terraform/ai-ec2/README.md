# AI 전용 EC2

기존 API EC2와 같은 VPC에 별도 `t3.small`을 만듭니다. 외부 모델을 호출하는 경량 서버이며 모델 가중치를 올리지 않습니다. Terraform state는 API/RDS 스택과 분리합니다. 기존 VPC·subnet·API SG는 참조만 하고 수정하지 않습니다.

- API SG → AI 사설 IP `8443`만 수신합니다. SSH·공개 HTTP 수신은 없습니다.
- public IPv4는 SSM·ECR·모델 API 아웃바운드용입니다. NAT Gateway를 추가하지 않습니다.
- EBS 암호화, IMDSv2, metadata hop limit 1, CPU credit standard를 사용합니다.
- AI role은 전용 ECR pull과 `/rougether-dev/llm/api-key`, `/rougether-dev/ai/runtime`만 읽습니다. DB·S3 업무 권한은 없습니다.
- Terraform에 비밀 값을 전달하지 않습니다. state에는 IAM parameter ARN만 남습니다.

## 생성

```bash
cp terraform.tfvars.example terraform.tfvars
# 실제 기존 VPC/subnet/API SG/deploy role을 입력합니다.
terraform init \
  -backend-config=bucket=STATE_BUCKET \
  -backend-config=key=ai-service/terraform.tfstate \
  -backend-config=region=ap-northeast-2 \
  -backend-config=encrypt=true \
  -backend-config=dynamodb_table=STATE_LOCK_TABLE
terraform plan -out=ai.tfplan
terraform apply ai.tfplan
```

backend 버킷은 암호화·버전 관리·공개 차단이 설정되어 있어야 합니다. 기존 API state를 복사하거나 API 스택 전체를 apply하지 않습니다. 초기 user-data는 Docker·Nginx만 준비합니다. image와 secret이 없는 상태에서 서비스가 자동으로 열리지 않습니다. AL2023 AMI/user-data 변경으로 자동 인스턴스 교체가 일어나지 않으므로 스크립트 갱신은 SSM 배포로 전달합니다.

## TLS와 런타임 설정

내부 전용 CA로 AI 사설 IP SAN을 가진 서버 인증서를 발급합니다. leaf 인증서는 1년 이내로 발급하며 만료 30일 전에 같은 CA로 갱신합니다. CA 개인키는 운영자 전용 저장소에 보관하고 EC2·Terraform·Git에는 전달하지 않습니다. 서버 개인키는 SSM SecureString에만 전달합니다. leaf 갱신은 동일 CA를 유지하므로 Spring 재배포가 필요 없습니다. CA 교체는 양쪽 신뢰 전환을 별도 계획해야 합니다.

SSM SecureString `/rougether-dev/ai/runtime`의 JSON 구조:

```json
{
  "private_ip": "10.39.10.50",
  "certificate": "PEM leaf certificate",
  "private_key": "PEM server private key",
  "ca_certificate": "PEM CA certificate",
  "env": {
    "AI_SERVICE_TOKEN": "별도로 생성한 32자 이상의 내부 토큰",
    "AI_PROVIDER_BASE_URL": "https://api.openai.com/v1",
    "AI_CHAT_MODEL": "gpt-5.6-luna",
    "AI_EMBEDDING_MODEL": "text-embedding-3-large",
    "AI_EMBEDDING_DIMENSIONS": "1024",
    "AI_CHAT_CONCURRENCY": "2",
    "AI_EMBEDDING_CONCURRENCY": "2"
  }
}
```

기존 공급자 키는 별도 `/rougether-dev/llm/api-key`에서 읽습니다. 생성된 EC2 private IP와 certificate SAN, client URL이 일치해야 합니다. Secret은 CLI 인수나 명령 로그에 직접 쓰지 않고 권한 `600` 파일 또는 SDK 메모리에서 전달합니다.

## 독립 배포와 복구

`AI service` workflow는 main의 AI 관련 변경을 검사하고 테스트 통과 후 immutable SHA tag를 ECR에 push합니다. `AI_SERVICE_INSTANCE_ID`를 지정하고 `AI_SERVICE_DEPLOY_ENABLED=true`로 설정하면 해당 AI EC2만 배포합니다. Spring 이미지·DB는 변경하지 않습니다. PR에서는 AWS 자격증명 없이 build까지만 수행합니다.

수동 실행/이전 digest로 복구:

```bash
AWS_PROFILE=rougether-isb AWS_REGION=ap-northeast-2 \
  python3 deploy/ai-service/run-deploy.py INSTANCE_ID REGISTRY/rougether-dev/ai-service@sha256:DIGEST
```

배포는 단일 lock을 잡고 inactive port에 후보를 띄웁니다. process health에 이어 실제 임베딩·completion을 호출한 뒤 Nginx를 전환합니다. 후보 실패 시 기존 서비스는 유지하며, 전환 후 TLS 확인 실패는 기존 Nginx 설정으로 복구합니다. 성공 후 기존 요청을 100초 동안 drain하고 이전 컨테이너를 종료합니다. Docker restart policy와 systemd Nginx로 재부팅 후 현재 슬롯이 복원됩니다. 이미지당 512MiB/1 CPU, swap 금지, non-root, read-only FS, cap drop, 로그 회전을 적용합니다. 배포 중 후보를 포함해 최대 1GiB의 컨테이너 메모리 제한이 잡힙니다.

`/etc/rougether-ai/state.json`에 현재/이전 digest가 남습니다. ECR에 release digest를 자동 삭제하는 lifecycle은 설정하지 않았습니다. 중단된 SSM 명령은 상태를 확인한 뒤 재실행합니다. 자동 rollback은 비즈니스 의미를 판별하지 않으므로 운영 오류는 이전 digest로 수동 복구합니다.

## Spring 전환

API role에 `/rougether-dev/ai/client` 정확한 ARN의 GetParameter 권한 및 기존 Deny allowlist 예외가 필요합니다. 정본은 `../ec2/main.tf`에 반영되어 있습니다. API와 batch의 기존 직접 호출 키는 최초 전환의 rollback용으로 보존합니다.

`/rougether-dev/ai/client` SecureString JSON은 다음 6개 문자열을 포함합니다.

- `AI_SERVICE_ENABLED`: `true` 또는 복구 시 `false`
- `AI_SERVICE_BASE_URL`: `https://AI_PRIVATE_IP:8443`
- `AI_SERVICE_TOKEN`: AI runtime과 동일한 내부 토큰
- `AI_SERVICE_TIMEOUT`: `95s`
- `AI_SERVICE_ALLOW_INSECURE_HTTP`: `false`
- `AI_SERVICE_CA_CERTIFICATE_BASE64`: 공개 CA PEM의 Base64 값

API EC2에서 `deploy/ai-service/configure-client.py user-api`를 실행하고 user-api를 배포한 뒤 실제 요청을 검증합니다. 이후 `configure-client.py batch`와 batch 배포로 전환합니다. 스크립트는 env만 원자적으로 쓰며 프로세스를 재시작하지 않습니다. 최초 이전 env는 `.env.before-ai`로 보존합니다. 기존 Spring 배포는 AI env 값을 보존합니다. **API EC2를 새로 만들면 이 구성 단계를 다시 수행해야 합니다.**

장애 시 client SSM의 `AI_SERVICE_ENABLED=false`를 반영하고 해당 앱을 재기동하면 기존 direct/stub 선택으로 돌아갑니다. API health만으로 provider 연결을 판단하지 않습니다. `qa/ai-service-contract/smoke.py`는 같은 CA와 내부 토큰으로 실제 호출을 검사합니다. AI 토큰/인증서 개인키/응답 본문은 로그에 출력하지 않습니다.

Spring→AI 호출은 여전히 동기식입니다. 별도 EC2는 자원·프로세스 장애를 격리하지만 Spring 대기 스레드를 없애지는 않습니다. AI별 동시성 제한·deadline·기존 fallback을 함께 사용합니다. 단일 AI EC2 장애 시 AI 기능은 저하되며 다중 AZ 고가용성은 이번 범위에 포함하지 않습니다.
