# Rougether EC2 Terraform

This stack is a small team/dev deployment:

- EC2 Amazon Linux 2023 instance
- RDS MySQL in the default VPC
- `user-api` on port `8080`
- `admin-api` on port `8081`
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

PRs into `main` or `feat/admin-assets` run `.github/workflows/pr-gate.yml`:

1. run `./gradlew test`
2. build both Docker images without pushing, to catch Dockerfile/module packaging failures

After the stack creates the GitHub Actions deploy role, pushes to `main` or
`feat/admin-assets` run `.github/workflows/docker-publish.yml`:

1. run `./gradlew test`
2. build `user-api` and `admin-api` as `linux/amd64`
3. push both images to ECR with `:dev` and commit SHA tags
4. deploy the immutable commit SHA tags through SSM
5. restart the EC2 systemd services and verify local health endpoints
6. verify public health endpoints

The SSM deploy script records the previously deployed images before restarting
the services. If the new `user-api` or `admin-api` image fails its local health
check, the script rewrites the systemd image env files back to the previous
images and restarts both services. The GitHub Actions run still fails so the
bad deployment is visible, but the EC2 service is rolled back when a previous
image is available.

Manual local build examples remain useful for bootstrap or debugging. Build, tag,
and push `:dev` images before replacing the EC2 instance:

```bash
terraform apply \
  -target=aws_ecr_repository.user_api \
  -target=aws_ecr_repository.admin_api

REGISTRY="$(terraform output -raw ecr_registry_server)"
aws ecr get-login-password --region ap-northeast-2 \
  | docker login "$REGISTRY" --username AWS --password-stdin

docker build --build-arg APP_MODULE=user-api -t rougether-user-api:local .
docker build --build-arg APP_MODULE=admin-api -t rougether-admin-api:local .

docker tag rougether-user-api:local "$REGISTRY/rougether-dev/user-api:dev"
docker tag rougether-admin-api:local "$REGISTRY/rougether-dev/admin-api:dev"

docker push "$REGISTRY/rougether-dev/user-api:dev"
docker push "$REGISTRY/rougether-dev/admin-api:dev"
```

Terraform-managed ECR is the default registry for this dev stack. To deploy from a separate private registry instead, set:

```hcl
user_api_image                            = "REGISTRY/user-api:TAG"
admin_api_image                           = "REGISTRY/admin-api:TAG"
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

```bash
deploy/scripts/put-firebase-credentials.sh /path/to/firebase-adminsdk.json
```

이후 GitHub Actions 배포는 매번 SecureString을 다시 읽어
`/etc/rougether/firebase-adminsdk.json`에 권한 `600`으로 원자적으로 교체하고,
user-api 컨테이너에 read-only로 마운트합니다. 키를 교체할 때도 같은 스크립트를 실행한 뒤
배포 workflow를 다시 실행하면 됩니다.

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
sudo journalctl -u rougether-user-api -f
sudo journalctl -u rougether-admin-api -f
sudo docker ps
sudo docker logs -f rougether-user-api
sudo docker logs -f rougether-admin-api
sudo tail -f /var/log/rougether-user-data.log
```

## Destroy

This stack defaults to `db_skip_final_snapshot = true` for low-friction dev teardown.
Change it before using this as a real production environment.

```bash
terraform destroy
```
