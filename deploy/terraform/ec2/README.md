# Rougether EC2 Terraform

This stack is a small team/dev deployment:

- EC2 Amazon Linux 2023 instance
- RDS MySQL in the default VPC
- `user-api` on port `8080`
- `admin-api` on port `8081`
- EC2 instance role for S3 uploads to the existing asset bucket
- SSM SecureString parameters for runtime secrets
- SSM Session Manager access by default, with optional SSH
- Docker containers pulled from GHCR

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
- Set `user_api_image` and `admin_api_image` to the GHCR tags built by GitHub Actions.
- If GHCR packages are private, set `container_registry_username` and `container_registry_password_ssm_parameter`.

Do not commit `terraform.tfvars` or Terraform state.

## Build Images

Docker images are built by `.github/workflows/docker-publish.yml`.

- `user-api` image: `ghcr.io/triples-soma/rougether-user-api`
- `admin-api` image: `ghcr.io/triples-soma/rougether-admin-api`
- PR/dev branch tag: `dev`
- `main` branch tag: `latest`
- Every push also gets a commit SHA tag.

Manual local build examples:

```bash
docker build --build-arg APP_MODULE=user-api -t rougether-user-api:local .
docker build --build-arg APP_MODULE=admin-api -t rougether-admin-api:local .
```

For private GHCR packages, create a GitHub token with package read access and store it in SSM:

```bash
aws ssm put-parameter \
  --name /rougether-dev/ghcr/token \
  --type SecureString \
  --value "YOUR_GITHUB_TOKEN" \
  --region ap-northeast-2
```

Then set:

```hcl
container_registry_username               = "YOUR_GITHUB_USERNAME"
container_registry_password_ssm_parameter = "/rougether-dev/ghcr/token"
```

## AWS Permissions

The IAM identity running Terraform needs permission to manage:

- EC2 instance, security groups, AMI/VPC/subnet lookups
- RDS MySQL and DB subnet groups
- IAM role, instance profile, role policy, and `iam:PassRole`
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
