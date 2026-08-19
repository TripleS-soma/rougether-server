# Rougether EC2 Terraform

This stack is a small team/dev deployment:

- EC2 Amazon Linux 2023 instance
- RDS MySQL in the default VPC, or in a Terraform-managed VPC for accounts without one
- `user-api` on port `8080`, fronted by CloudFront for HTTPS (`*.cloudfront.net`)
- `admin-api` on port `8081`
- `batch` (루틴 리마인드 발송) — 외부 접근 없이 `127.0.0.1:8082` 헬스체크만 노출
- EC2 instance role for scoped S3 access
- Optional private, encrypted S3 asset bucket exposed only through CloudFront OAC
- SSM SecureString parameters for runtime secrets
- SSM Session Manager access by default, with optional SSH
- Private ECR repositories for Docker images

It is intentionally simpler than ECS/Fargate. Use this for an early team environment, not a hardened production deployment.

## Cost Notes

Check the AWS console before applying. AWS Free Tier eligibility depends on account creation date and current AWS offers. The application host defaults to `t3.medium`, but that is a baseline rather than a guarantee that the active workload and one candidate fit together; the live memory preflight decides whether activation is safe. This size is generally billable. AWS documents current EC2 and RDS Free Tier behavior in the EC2/RDS docs, and T3 instances can incur CPU credit charges if Unlimited mode is used. This Terraform sets EC2 CPU credits to `standard`.

## Prepare

```bash
cd deploy/terraform/ec2
cp terraform.tfvars.example terraform.tfvars
```

Edit `terraform.tfvars`:

- Keep `allowed_admin_api_cidrs = []`. The admin API is reachable through its dedicated CloudFront
  HTTPS URL, with direct EC2 ingress forbidden. The encrypted SSM tunnel remains an emergency path.
- Keep `allowed_ssh_cidrs = []` unless you need SSH.
- Set `create_network = true` when the account has no default VPC. Terraform creates two
  public subnets in separate AZs; RDS itself remains non-public and accepts MySQL only from EC2.
- Set `create_asset_bucket = true`. By default, Terraform generates
  `<project>-<environment>-<AWS account ID>-assets`; override `asset_bucket_name` only when the
  target bucket name is already known and globally unique. When adopting an existing bucket,
  import it into this stack before applying. Reads go through the generated CloudFront URL while
  direct public S3 access stays blocked. `bug-reports/*` is intentionally excluded from the
  public CDN and is read only through authenticated user/admin APIs. Profile reads use a no-cache
  behavior so withdrawal deletion is not retained at the edge.
- External asset buckets are rejected by this module because their existing public policy cannot
  guarantee that `bug-reports/*` is private. Migrate objects into the managed bucket before cutover.
- Set `admin_seed_password` or let Terraform generate one.
- Leave `user_api_image`, `admin_api_image`, and `container_registry_server` as `null` to use the Terraform-managed private ECR repositories.
- Override image and registry variables only when deploying from another registry.

Do not commit `terraform.tfvars` or Terraform state.
Firebase 서비스 계정 JSON도 `terraform.tfvars`에 넣지 않습니다. 실제 값은 아래 전용 스크립트로 SSM에 직접 등록합니다.
Kakao Admin key와 Apple team/key/private/encryption key도 `social_auth_parameter_names` output의
SSM SecureString에 저장해야 합니다. EC2 role은 이 정확한 parameter들만 읽으며 user-data가
`user-api.env`에 주입합니다. 값이 없으면 서버는 기동하지만 Kakao unlink와 Apple 로그인/revoke는
fail-closed로 실패합니다.

## Build Images

Terraform creates these private ECR repositories:

- `rougether-dev/user-api`
- `rougether-dev/admin-api`
- `rougether-dev/batch`

### batch 롤아웃 순서 (기존 환경)

batch 컨테이너를 처음 도입할 때는 **Terraform 을 먼저 적용한 뒤** main push workflow 를
활성화해야 한다. `docker-publish.yml` 은 main push 시 `rougether-dev/batch` 로 `:unverified-<sha>`
이미지를 push하고, 테스트 통과 후 `:sha` 로, 배포와 health 검증이 끝난 뒤 `:dev` 로 승격한다.
그 저장소와 GitHub Actions 배포
role 의 push·승격·복원 권한은 이 스택의 Terraform 으로만 생성되기 때문이다. 선적용하지 않으면
첫 workflow 가 `RepositoryNotFound` 또는 `AccessDenied` 로 실패한다.

```bash
# 1) batch ECR 레포·lifecycle·IAM 권한을 먼저 만든다 (기존 인스턴스 재생성 없이 안전)
terraform apply \
  -target=aws_ecr_repository.batch \
  -target=aws_ecr_lifecycle_policy.batch \
  -target=aws_iam_role_policy.app \
  -target=aws_iam_role_policy.github_actions_deploy
# 2) 이후 main push 배포가 batch 이미지를 push·배포한다.
```

기존 인스턴스는 `user_data` 변경이 무시되므로 `batch.env` 가 없다. 배포 스크립트
(`deploy-ec2-with-rollback.sh`)가 최초 배포 시 `user-api.env` 의 DB 접속을 복사해 `batch.env` 를
자동 생성하므로 인스턴스 재생성은 필요 없다.

PRs into `main` run `.github/workflows/pr-gate.yml`:

1. test the deployment and ECR tag-promotion scripts, then run `./gradlew test`
2. build the `user-api`, `admin-api`, and `batch` Docker images without pushing, to catch Dockerfile/module packaging failures

After the stack creates the GitHub Actions deploy role, pushes to `main` run
`.github/workflows/docker-publish.yml`:

1. run `./gradlew test` while the image builds run in parallel
2. build `user-api`, `admin-api`, and `batch` as `linux/amd64`
3. push all images to ECR with `:unverified-<sha>` tags only — a bare `:sha` tag in ECR
   always means the commit passed the test gate
4. after both test and build succeed, promote `:unverified-<sha>` to the immutable
   commit SHA tags (manifest copy, seconds)
5. read `ROUGETHER_DEPLOY_MODE`; `hold` skips EC2 mutation, `legacy` uses the emergency hard-restart path, and `blue-green` performs the normal same-host switch
6. in `blue-green`, cold-start one inactive API slot, require three consecutive local health successes, atomically reload Nginx, verify the fixed port, drain for 30 seconds, and only then stop the previous slot
7. restart the single `batch` container once after both API switches and verify `127.0.0.1:8082/actuator/health`
8. verify public health endpoints (`user-api`, `admin-api`)
9. promote all three verified SHA images to `:dev`; if promotion partially fails,
   restore the previous complete tag set

The SSM deploy script records image, active color, active port, release SHA, and a
`deploying`/`ready` status atomically. Candidate failure before the switch leaves the active
slot untouched. Failure after the switch moves Nginx back to the previous slot and then
stops the failed candidate. If admin or batch fails, the already-switched user API is also
returned to the previous complete release set. The GitHub Actions run remains failed so the
bad release is visible.

### First blue/green activation

The repository variable defaults to `hold` when absent. Before merging the preparation PR,
set it explicitly and confirm the main workflow builds/tests but skips SSM and `:dev` promotion:

```bash
gh variable set ROUGETHER_DEPLOY_MODE --body hold
```

On the existing EC2, run `deploy/scripts/bootstrap-blue-green-router.sh` once without flags.
It installs the template units/Nginx and, only when the memory preflight passes, cold-starts each
blue candidate on `18080`/`18081`; legacy services and fixed ports remain unchanged. Inspect memory, systemd, and Nginx logs,
then run the same script with `--activate`. The one-time activation hands each fixed port to
Nginx and writes the blue slot state; its trap restores the legacy services if the handoff fails.

After direct and CloudFront health pass, enable normal deployments:

```bash
gh variable set ROUGETHER_DEPLOY_MODE --body blue-green
gh workflow run docker-publish.yml --ref main
```

`legacy` exists only for emergency recovery and brings back the former hard-restart behavior.
Do not run `terraform apply` for the `t3.medium` default until the active AWS account and state
lineage are confirmed and `terraform plan` shows no unintended instance replacement.

Manual local build examples remain useful for bootstrap or debugging. Build, tag,
and push `:dev` images before replacing the EC2 instance:

```bash
terraform apply \
  -target=aws_ecr_repository.user_api \
  -target=aws_ecr_repository.admin_api \
  -target=aws_ecr_repository.batch

REGISTRY="$(terraform output -raw ecr_registry_server)"
aws ecr get-login-password --region ap-northeast-2 \
  | docker login "$REGISTRY" --username AWS --password-stdin

docker build --build-arg APP_MODULE=user-api -t rougether-user-api:local .
docker build --build-arg APP_MODULE=admin-api -t rougether-admin-api:local .
docker build --build-arg APP_MODULE=batch -t rougether-batch:local .

docker tag rougether-user-api:local "$REGISTRY/rougether-dev/user-api:dev"
docker tag rougether-admin-api:local "$REGISTRY/rougether-dev/admin-api:dev"
docker tag rougether-batch:local "$REGISTRY/rougether-dev/batch:dev"

docker push "$REGISTRY/rougether-dev/user-api:dev"
docker push "$REGISTRY/rougether-dev/admin-api:dev"
docker push "$REGISTRY/rougether-dev/batch:dev"
```

Terraform-managed ECR is the default registry for this dev stack. To deploy from a separate private registry instead, set:

```hcl
user_api_image                            = "REGISTRY/user-api:TAG"
admin_api_image                           = "REGISTRY/admin-api:TAG"
batch_image                               = "REGISTRY/batch:TAG"
container_registry_server                 = "REGISTRY"
container_registry_username               = "USERNAME"
container_registry_password_ssm_parameter = "/path/to/token"
```

## AWS Permissions

The IAM identity running Terraform needs permission to manage:

- EC2 instance, security groups, AMI/VPC/subnet lookups
- RDS MySQL and DB subnet groups
- ECR repositories and lifecycle policies
- IAM role, instance profile, role policy, OIDC provider, and `iam:PassRole`
- SSM parameters
- Random local Terraform values

The current S3-only app key is not enough. A failed `terraform plan` with
`ec2:DescribeVpcs` or `ec2:DescribeImages` means the deploy identity still lacks
EC2 read permissions. For a quick dev bootstrap, use an admin/deployer identity,
apply the stack, then keep the runtime app permissions on the EC2 instance role.

## Deploy

```bash
terraform init
terraform plan -out=tfplan
terraform apply tfplan
```

After apply:

```bash
terraform output user_api_health_url
terraform output admin_url
terraform output admin_tunnel_command
terraform output ssm_session_command
terraform output asset_public_base_url
```

AWS IAM Identity Center를 쓰는 계정은 로그인한 profile을 Terraform과 보조 스크립트에 동일하게
전달합니다.

```bash
aws sso login --profile rougether-isb
AWS_PROFILE=rougether-isb terraform plan -out=tfplan
AWS_PROFILE=rougether-isb terraform apply tfplan
AWS_PROFILE=rougether-isb ../../scripts/db-tunnel.sh
```

GitHub Actions 배포 계정을 옮길 때는 저장소 variable `AWS_DEPLOY_ROLE_ARN`에 새 stack의
`github_actions_deploy_role_arn` output을 설정합니다. variable을 설정하기 전에는 workflow가 기존
계정 role을 fallback으로 사용하므로, 새 환경 검증과 애플리케이션 endpoint 전환을 끝낸 뒤 변경합니다.

If Terraform generated the admin password, read it from SSM:

```bash
PARAM="$(terraform output -raw admin_seed_password_parameter)"
aws ssm get-parameter \
  --name "$PARAM" \
  --with-decryption \
  --query 'Parameter.Value' \
  --output text \
  --region ap-northeast-2
```

## Firebase 서비스 계정 키

Firebase SecureString은 Terraform state 밖에서 관리하고, Terraform은
`/${project_name}-${environment}/firebase/credentials-json`의 정확한 ARN에 대한 EC2 조회 권한만
관리합니다. `aws_ssm_parameter` 리소스로 선언하면 refresh 때 복호화된 값이 state에 들어갈 수 있으므로
실제 JSON을 Terraform 변수나 리소스로 전달하지 않습니다.

Firebase Console에서 받은 파일은 전용 스크립트로 등록합니다. 파라미터가 없으면 생성하고,
이미 있으면 새 버전으로 교체합니다. 스크립트는 서비스 계정 필수 필드와 SSM Standard의 4KB 제한을
확인하며 키 값은 출력하지 않습니다.

신규 스택은 가능하면 `terraform apply` 전에 키를 먼저 등록합니다. SSM 조회가 일시적으로 실패해도
인스턴스 전체 부트스트랩은 계속되며, 기존 정상 키가 있으면 유지하고 없으면 user-api·batch가
`StubFcmSender`로 기동합니다. 다음 배포에서 다시 SSM 조회를 시도합니다. batch(루틴 리마인드
발송)는 키가 없으면 푸시를 보내지 못하고 알림을 `FAILED`로 기록하므로, 리마인드를 실제 발송하려면
키 등록이 필요합니다.

```bash
deploy/scripts/put-firebase-credentials.sh /path/to/firebase-adminsdk.json
```

이후 GitHub Actions 배포는 매번 SecureString을 다시 읽어
`/etc/rougether/firebase-adminsdk.json`에 권한 `600`으로 원자적으로 교체하고,
user-api·batch 컨테이너에 read-only로 마운트합니다(키 유효성에 맞춰 각 `*.env`의
`FIREBASE_CREDENTIALS_PATH`도 매 배포 재조정). 키를 교체할 때도 같은 스크립트를 실행한 뒤
배포 workflow를 다시 실행하면 됩니다. 새 키 때문에 health check가 실패하면 이미지와 함께 이전 키도
복원합니다.

파라미터 이름을 바꾼 환경에서는 업로드와 workflow의 값을 함께 맞춥니다.

```bash
FIREBASE_CREDENTIALS_PARAMETER=/rougether-staging/firebase/credentials-json \
ENVIRONMENT_TAG=staging \
  deploy/scripts/put-firebase-credentials.sh /path/to/firebase-adminsdk.json
```

## Webex 운영 오류 알림

user-api는 다음 오류만 기존 `Rougether Git Bot`을 통해 Webex 스페이스로 비동기 전송합니다.

- `AUTH_OAUTH_*` 소셜 로그인 토큰·인증 서버 오류
- 모든 5xx `BusinessException`
- 예기치 않은 500 오류

일반 4xx는 알림 폭주를 막기 위해 제외합니다. 5xx는 같은 오류 코드를 기본 1분 동안 한 번만 보내고,
공개 endpoint에서 발생하는 OAuth 토큰 무효 오류는 공급자 전체를 묶어 기본 1시간에 한 번만 보냅니다.
메시지에는 요청 본문이나 토큰을 넣지 않으며 mention도 무력화합니다. Webex bot token은 Terraform state나
저장소에 넣지 않고 `/${project_name}-${environment}/alerts/webex-bot-token` SecureString으로 관리합니다.
room ID도 실제 값을 저장소에 넣지 않고 `/${project_name}-${environment}/alerts/webex-room-id` String으로
동기화해 인스턴스 교체 시 user-data가 함께 복원합니다.

GitHub 저장소에는 기존 API 변경 알림과 동일하게 다음 값을 설정합니다.

- secret `WEBEX_BOT_TOKEN`
- variable `WEBEX_ROOM_ID`

봇은 해당 Webex 스페이스의 멤버여야 합니다. Terraform이 GitHub Actions role의 정확한 parameter ARN
쓰기 권한과 EC2 role의 조회 권한을 관리하므로 권한 변경을 먼저 적용합니다. 이후 main 배포가 기존
GitHub secret과 variable을 SSM으로 동기화하고 `/etc/rougether/user-api.env`에 원자적으로 반영합니다.
SSM 조회 또는 토큰 검증이 실패하면 기존 정상 토큰을 유지하며, 등록된 토큰이 전혀 없으면 알림만
비활성화되고 API는 정상 기동합니다.

```bash
terraform plan -out=tfplan
terraform apply tfplan
```

파라미터 이름이나 Webex 스페이스를 바꾼 환경에서는 deploy workflow의 parameter 이름과
GitHub variable `WEBEX_ROOM_ID`를 함께 맞춥니다.

## 주간 회고 LLM API 키

batch의 AI 주간 회고(`weeklyReportJob`)는 OpenAI 호환 chat/completions API를 호출합니다. API 키는 Terraform state나
저장소에 넣지 않고 `/${project_name}-${environment}/llm/api-key` SecureString으로만 관리합니다.
EC2 role은 이 parameter만 읽고, user-data가 최초 부트스트랩 때 `/etc/rougether/batch.env`와 `/etc/rougether/user-api.env`의
`LLM_API_KEY`로 쓰며(batch는 주간 회고, user-api는 유사 루틴 비교 임베딩), 이후 GitHub Actions 배포가 매번 SecureString을
다시 읽어 같은 파일들에 원자적으로 반영합니다(`refresh_llm_env`).
키가 없거나 형식이 이상하면 기존 값을 유지하고, 끝내 비어 있으면 batch는 LLM stub으로 기동해 **주간 회고 생성만
보류**합니다(다른 배치는 영향 없음, 가짜 회고는 저장되지 않음).

### 동거 봇 활성 (ROUGETHER_BOTS_ENABLED)

동거 봇(#307~#310)은 user-api 의 `ROUGETHER_BOTS_ENABLED` 로 켭니다. GitHub Actions 배포가 매 배포 repo variable `ROUGETHER_BOTS_ENABLED`(미설정 시 dev 기본 `true`)를 `/etc/rougether/user-api.env` 에 반영하고(`refresh_bots_env`), 켜진 채 기동하면 `BotSeeder` 가 카탈로그 봇 6명을 시드하고 활동 스케줄러가 돕니다. 끄려면 variable 을 `false` 로 두고 다시 배포합니다(이미 만들어진 봇 계정은 남고 활동만 멈춤).

키 등록은 운영자가 직접 합니다(값을 CI나 저장소에 두지 않습니다).

```bash
aws ssm put-parameter \
  --name /rougether-dev/llm/api-key \
  --description "Rougether weekly report LLM API key" \
  --type SecureString --tier Standard \
  --value 'sk-...' \
  --tags Key=Project,Value=rougether Key=Environment,Value=dev \
  --region ap-northeast-2
```

이미 있으면 `--overwrite`로 교체합니다(키를 교체(rotate)한 뒤 다음 main 배포 또는 `systemctl restart rougether-batch`
전에 배포 스크립트가 재조회). 권한(EC2 role의 parameter ARN allowlist)은 Terraform이 관리하므로 먼저 적용합니다.

```bash
terraform plan -out=tfplan
terraform apply tfplan
```

모델·추론 강도는 필요 시 같은 `batch.env`에 `LLM_MODEL`, `LLM_REASONING_EFFORT`로 덮어쓸 수 있고, 없으면
애플리케이션 기본값(`gpt-5.6-luna`, `low`)을 씁니다.

## HTTPS (CloudFront)

iOS ATS(App Transport Security)가 앱의 평문 HTTP 호출을 기본 차단하기 때문에, 앱스토어 제출용으로 user-api 앞에 CloudFront 를 둡니다(`cloudfront.tf`). 도메인 구매 없이 `xxxx.cloudfront.net` 기본 도메인과 기본 인증서(TLS 1.2+)로 ATS 요건을 충족합니다.

- 앱(RN)의 API base URL 은 반드시 CloudFront 주소를 사용합니다.

```bash
terraform output -raw user_api_https_base_url
```

- 캐시는 전부 비활성화(Managed-CachingDisabled)이고, Authorization 헤더·쿼리스트링·쿠키를 모두 origin 으로 전달합니다(Managed-AllViewerExceptHostHeader).
- admin-api(:8081)는 별도 CloudFront HTTPS 배포로 접근합니다. CloudFront는 private subnet의 내부 NLB를 VPC origin으로 사용하고, EC2는 해당 NLB 보안그룹의 `:8081` 연결만 허용합니다. 따라서 관리자 인증 정보는 CloudFront 뒤의 공용 인터넷 구간을 통과하지 않습니다.
- 애플리케이션은 해당 배포가 주입하는 SSM 관리 origin 비밀값까지 검증합니다. public IP `:8081` 직접 접속과 다른 CloudFront 배포를 통한 우회는 차단되며 SSM 포트 포워딩은 비상 접근 경로로 유지합니다. 내부 NLB는 별도 AWS 시간당/LCU 비용이 발생합니다.
- `:8080` 직접 HTTP 접속은 배포 workflow 의 public health check 가 사용하므로 계속 열려 있습니다.
- origin 은 EIP(`aws_eip.app`)의 public DNS 라 EC2 stop/start·재생성에도 유지됩니다. 재생성 시에는 같은 apply 에서 EIP 연결(`aws_eip_association`)이 새 인스턴스로 옮겨집니다. EIP 는 2024-02 이후 모든 public IPv4 와 동일 과금이라 추가 비용이 없습니다.
- 배포 workflow 는 direct HTTP 와 함께 CloudFront 경유 health check 도 수행합니다(배포가 origin 까지 실제로 도달하는지 검증). CloudFront 배포가 아직 없으면(조회는 성공했지만 결과가 빈 경우) 그 단계만 경고 후 건너뛰고, 조회 자체가 실패하면(권한 부족·API 오류) 배포를 실패시킵니다.
- **롤아웃 순서**: main push 는 즉시 배포 workflow 를 실행하므로 "머지 후 apply" 라는 순서는 존재하지 않습니다. batch ECR 도입 때와 동일하게 **머지 전에** 이 변경이 담긴 브랜치 기준으로 Terraform 을 선적용합니다. deploy role 의 `cloudfront:ListDistributions` 권한이 Terraform 으로만 생성되기 때문입니다.
- 선적용 없이 머지된 경우: 첫 배포가 HTTPS health check 단계에서 AccessDenied 로 실패합니다. 이 시점에 컨테이너 배포와 로컬 health check 는 이미 성공했고 `:dev` 승격만 건너뛴 상태이므로, `terraform apply` 후 실패한 workflow run 을 re-run 하면 복구됩니다(서비스 중단 없음).

```bash
# 머지 전 필수 선적용 (이 브랜치 checkout 상태에서 전체 plan을 검토한 뒤 적용)
terraform plan -out=tfplan
terraform apply tfplan
```
- 정식 도메인을 확보하면 `aliases` + ACM(us-east-1) 인증서를 붙이는 것으로 전환합니다.

최초 생성/변경 배포에는 5~10분 정도 걸립니다.

## Health Checks

```bash
curl "$(terraform output -raw user_api_health_url)"
curl "$(terraform output -raw user_api_https_health_url)"
```

Admin은 별도 터미널에서 SSM 터널을 유지한 뒤 로컬 주소로 확인합니다.

```bash
$(terraform output -raw admin_tunnel_command)
```

```bash
curl "$(terraform output -raw admin_health_url)"
open "$(terraform output -raw admin_url)"
```

## Inspect EC2

Use SSM Session Manager:

```bash
$(terraform output -raw ssm_session_command)
```

Useful commands on EC2:

```bash
sudo systemctl status rougether-user-api
sudo systemctl status rougether-admin-api
sudo systemctl status rougether-batch
sudo journalctl -u rougether-user-api -f
sudo journalctl -u rougether-admin-api -f
sudo journalctl -u rougether-batch -f
sudo docker ps
sudo docker logs -f rougether-user-api
sudo docker logs -f rougether-admin-api
sudo docker logs -f rougether-batch
# batch 리마인드 발송 로그(5분 주기) 확인 + 인스턴스 안에서 헬스체크
curl -fsS http://127.0.0.1:8082/actuator/health
sudo tail -f /var/log/rougether-user-data.log
```

## Destroy

This stack defaults to `db_skip_final_snapshot = true` for low-friction dev teardown.
Change it before using this as a real production environment.

```bash
terraform destroy
```
