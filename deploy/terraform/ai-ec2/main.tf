terraform {
  required_version = ">= 1.5.0"
  backend "s3" {}
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 6.0" }
  }
}
provider "aws" { region = var.aws_region }
variable "aws_region" { default = "ap-northeast-2" }
variable "name" { default = "rougether-dev" }
variable "vpc_id" { type = string }
variable "subnet_id" { type = string }
variable "api_security_group_id" { type = string }
variable "github_deploy_role_name" { type = string }
variable "instance_type" { default = "t3.small" }
data "aws_caller_identity" "current" {}
data "aws_subnet" "selected" { id = var.subnet_id }
data "aws_security_group" "api" { id = var.api_security_group_id }
data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]
  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-x86_64"]
  }
  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}
locals {
  tags           = { Project = "rougether", Environment = "dev", Component = "ai-service", ManagedBy = "terraform" }
  parameters     = ["/${var.name}/llm/api-key", "/${var.name}/ai/runtime"]
  parameter_arns = [for name in local.parameters : "arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter${name}"]
}
resource "aws_security_group" "ai" {
  name        = "${var.name}-ai"
  description = "AI TLS from application EC2 only"
  vpc_id      = var.vpc_id
  ingress {
    description     = "Private HTTPS from API and batch host"
    protocol        = "tcp"
    from_port       = 8443
    to_port         = 8443
    security_groups = [var.api_security_group_id]
  }
  egress {
    description = "Model provider, ECR and SSM HTTPS"
    protocol    = "tcp"
    from_port   = 443
    to_port     = 443
    cidr_blocks = ["0.0.0.0/0"]
  }
  # AL2023 패키지 저장소 접근에 사용함.
  egress {
    description = "OS package repositories"
    protocol    = "tcp"
    from_port   = 80
    to_port     = 80
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = local.tags
}
resource "aws_ecr_repository" "ai" {
  name                 = "${var.name}/ai-service"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration { scan_on_push = true }
  encryption_configuration { encryption_type = "AES256" }
  tags = local.tags
}
resource "aws_iam_role" "ai" {
  name               = "${var.name}-ai-ec2"
  assume_role_policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Action = "sts:AssumeRole", Principal = { Service = "ec2.amazonaws.com" } }] })
  tags               = local.tags
}
resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.ai.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}
resource "aws_iam_role_policy" "runtime" {
  name = "ai-runtime"
  role = aws_iam_role.ai.name
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Effect = "Allow", Action = ["ssm:GetParameter"], Resource = local.parameter_arns },
    { Effect = "Deny", Action = ["ssm:GetParameter", "ssm:GetParameters", "ssm:GetParameterHistory"], NotResource = local.parameter_arns },
    { Effect = "Deny", Action = ["ssm:GetParametersByPath"], Resource = "*" },
    { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
    { Effect = "Allow", Action = ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:BatchCheckLayerAvailability"], Resource = aws_ecr_repository.ai.arn }
  ] })
}
resource "aws_iam_instance_profile" "ai" {
  name = "${var.name}-ai-ec2"
  role = aws_iam_role.ai.name
}
resource "aws_instance" "ai" {
  ami                    = data.aws_ami.al2023.id
  instance_type          = var.instance_type
  subnet_id              = var.subnet_id
  vpc_security_group_ids = [aws_security_group.ai.id]
  # 인터넷 수신은 SG로 금지하고 외부 공급자/SSM/ECR 아웃바운드에만 public IPv4를 사용함.
  associate_public_ip_address = true
  iam_instance_profile        = aws_iam_instance_profile.ai.name
  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }
  root_block_device {
    volume_size = 16
    volume_type = "gp3"
    encrypted   = true
  }
  credit_specification { cpu_credits = "standard" }
  user_data = templatefile("${path.module}/templates/bootstrap.sh.tftpl", {
    deploy_script = filebase64("${path.module}/../../ai-service/deploy.py")
  })
  lifecycle {
    ignore_changes = [ami, user_data]
    precondition {
      condition     = data.aws_subnet.selected.vpc_id == var.vpc_id && data.aws_security_group.api.vpc_id == var.vpc_id
      error_message = "AI subnet과 API 보안 그룹은 동일한 VPC여야 합니다."
    }
  }
  tags       = merge(local.tags, { Name = "${var.name}-ai" })
  depends_on = [aws_iam_role_policy.runtime, aws_iam_role_policy_attachment.ssm]
}
resource "aws_iam_role_policy" "deploy" {
  name = "${var.name}-ai-deploy"
  role = var.github_deploy_role_name
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
    { Effect = "Allow", Action = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:CompleteLayerUpload", "ecr:DescribeImages", "ecr:GetDownloadUrlForLayer", "ecr:InitiateLayerUpload", "ecr:PutImage", "ecr:UploadLayerPart"], Resource = aws_ecr_repository.ai.arn },
    { Effect = "Allow", Action = ["ssm:SendCommand"], Resource = ["arn:aws:ssm:${var.aws_region}::document/AWS-RunShellScript", aws_instance.ai.arn] },
    { Effect = "Allow", Action = ["ssm:GetCommandInvocation"], Resource = "*" }
  ] })
}
output "instance_id" { value = aws_instance.ai.id }
output "private_ip" { value = aws_instance.ai.private_ip }
output "repository_url" { value = aws_ecr_repository.ai.repository_url }
output "security_group_id" { value = aws_security_group.ai.id }
