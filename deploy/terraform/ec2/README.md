# Rougether EC2 Terraform

This stack is a small team/dev deployment:

- EC2 Amazon Linux 2023 instance
- RDS MySQL in the default VPC
- `user-api` on port `8080`
- `admin-api` on port `8081`
- `batch` (루틴 리마인드 발송) — 외부 접근 없이 `127.0.0.1:8082` 헬스체크만 노출
- EC2 instance role for S3 uploads to the existing asset bucket
- SSM SecureString parameters for runtime secrets
- SSM Session Manager access by default, with optional SSH
- Private ECR repositories for Docker images

It is intentionally simpler than ECS/Fargate. Use this for an early team environment, not a hardened production deployment.

## Cost Notes

Check the AWS console before applying. AWS Free Tier eligibility depends on account creation date and current AWS offers. AWS documents current EC2 and RDS Free Tier behavior in the EC2/RDS docs, and `t3.micro` can incur CPU credit charges if Unlimited mode is used. This Terraform sets EC2 CPU credits to `standard`.

## Prepare

```bash
cd deploy/terraform/ec2
cp terraform.tfvars.example terraform.tfvars
```

Edit `terraform.tfvars`:

- Set `allowed_admin_api_cidrs` to team/VPN public IP CIDRs.
- Keep `allowed_ssh_cidrs = []` unless you need SSH.
- Set `admin_seed_password` or let Terraform generate one.
- Leave `user_api_image`, `admin_api_image`, and `container_registry_server` as `null` to use the Terraform-managed private ECR repositories.
- Override image and registry variables only when deploying from another registry.

Do not commit `terraform.tfvars` or Terraform state.
Firebase 서비스 계정 JSON도 `terraform.tfvars`에 넣지 않습니다. 실제 값은 아래 전용 스크립트로 SSM에 직접 등록합니다.

## Build Images

Terraform creates these private ECR repositories:

- `rougether-dev/user-api`
- `rougether-dev/admin-api`
- `rougether-dev/batch`

### batch 롤아웃 순서 (기존 환경)

batch 컨테이너를 처음 도입할 때는 **Terraform 을 먼저 적용한 뒤** main push workflow 를
활성화해야 한다. `docker-publish.yml` 은 main push 시 곧바로 `rougether-dev/batch` 로 이미지를
push 하는데, 그 저장소와 GitHub Actions 배포 role 의 push 권한은 이 스택의 Terraform 으로만
생성되기 때문이다. 선적용하지 않으면 첫 workflow 가 `RepositoryNotFound` 또는 `AccessDenied` 로
실패한다.

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

PRs into `main` or `feat/admin-assets` run `.github/workflows/pr-gate.yml`:

1. run `./gradlew test`
2. build the `user-api`, `admin-api`, and `batch` Docker images without pushing, to catch Dockerfile/module packaging failures

After the stack creates the GitHub Actions deploy role, pushes to `main` or
`feat/admin-assets` run `.github/workflows/docker-publish.yml`:

1. run `./gradlew test`
2. build `user-api`, `admin-api`, and `batch` as `linux/amd64`
3. push all images to ECR with `:dev` and commit SHA tags
4. deploy the immutable commit SHA tags through SSM
5. restart the EC2 systemd services and verify local health endpoints
   (`batch` 는 `127.0.0.1:8082/actuator/health` 로 인스턴스 안에서만 확인)
6. verify public health endpoints (`user-api`, `admin-api`)

The SSM deploy script records the previously deployed images before restarting
the services. If the new `user-api`, `admin-api`, or `batch` image fails its local
health check, the script rewrites the systemd image env files back to the previous
images and restarts the services. `batch` 는 독립 유닛이라 최초 도입 배포처럼 이전
이미지가 없으면 롤백을 건너뛰고 새 유닛만 기동합니다. The GitHub Actions run still
fails so the bad deployment is visible, but the EC2 service is rolled back when a
previous image is available.

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
terraform output ssm_session_command
```

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

## Health Checks

```bash
curl "$(terraform output -raw user_api_health_url)"
curl "$(terraform output -raw admin_health_url)"
```

Open:

```bash
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
