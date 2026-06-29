variable "aws_region" {
  description = "AWS region for EC2, RDS, SSM, and IAM resources."
  type        = string
  default     = "ap-northeast-2"
}

variable "project_name" {
  description = "Project name used for resource names and tags."
  type        = string
  default     = "rougether"
}

variable "environment" {
  description = "Deployment environment name."
  type        = string
  default     = "dev"
}

variable "repository_url" {
  description = "Deprecated. Kept for compatibility with the old EC2 build flow."
  type        = string
  default     = "https://github.com/TripleS-soma/rougether-server.git"
}

variable "repository_branch" {
  description = "Deprecated. Kept for compatibility with the old EC2 build flow."
  type        = string
  default     = "feat/admin-assets"
}

variable "user_api_image" {
  description = "Docker image used for user-api. If null, Terraform-managed ECR is used."
  type        = string
  default     = null
}

variable "admin_api_image" {
  description = "Docker image used for admin-api. If null, Terraform-managed ECR is used."
  type        = string
  default     = null
}

variable "container_registry_server" {
  description = "Container registry server. If null, Terraform-managed ECR is used."
  type        = string
  default     = null
}

variable "container_registry_username" {
  description = "Optional external registry username. Leave null when using Terraform-managed ECR."
  type        = string
  default     = null
}

variable "container_registry_password_ssm_parameter" {
  description = "Optional SSM SecureString parameter name containing an external registry token. Leave null when using Terraform-managed ECR."
  type        = string
  default     = null
}

variable "instance_type" {
  description = "EC2 instance type. Check current AWS Free Tier eligibility before apply."
  type        = string
  default     = "t3.micro"
}

variable "key_name" {
  description = "Optional EC2 key pair name for SSH. Leave null and use SSM Session Manager."
  type        = string
  default     = null
}

variable "allowed_user_api_cidrs" {
  description = "CIDR blocks allowed to access user-api on port 8080."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "allowed_admin_api_cidrs" {
  description = "CIDR blocks allowed to access admin-api on port 8081. Prefer team/VPN IPs."
  type        = list(string)
  default     = []
}

variable "allowed_ssh_cidrs" {
  description = "CIDR blocks allowed to access SSH on port 22. Keep empty when using SSM."
  type        = list(string)
  default     = []
}

variable "root_volume_size" {
  description = "EC2 root EBS size in GiB."
  type        = number
  default     = 16
}

variable "db_instance_class" {
  description = "RDS MySQL instance class. Check current Free Tier eligibility before apply."
  type        = string
  default     = "db.t3.micro"
}

variable "db_allocated_storage" {
  description = "RDS allocated storage in GiB."
  type        = number
  default     = 20
}

variable "db_name" {
  description = "Application database name."
  type        = string
  default     = "rougether"
}

variable "db_username" {
  description = "RDS master username."
  type        = string
  default     = "rougether"
}

variable "db_backup_retention_period" {
  description = "RDS backup retention period in days. Use 0 for lowest-cost dev deployments."
  type        = number
  default     = 0
}

variable "db_deletion_protection" {
  description = "Enable RDS deletion protection."
  type        = bool
  default     = false
}

variable "db_skip_final_snapshot" {
  description = "Skip final snapshot when destroying RDS. Use false for real production."
  type        = bool
  default     = true
}

variable "asset_bucket_name" {
  description = "Existing public asset bucket name. Terraform does not create this bucket."
  type        = string
  default     = "rougether-assets"
}

variable "asset_region" {
  description = "S3 asset bucket region used by the Spring configuration."
  type        = string
  default     = "ap-northeast-2"
}

variable "asset_public_base_url" {
  description = "Public base URL used by admin preview links."
  type        = string
  default     = "https://rougether-assets.s3.ap-northeast-2.amazonaws.com"
}

variable "asset_allowed_prefixes" {
  description = "S3 key prefixes the EC2 instance role may write to."
  type        = list(string)
  default     = ["items/*", "characters/*", "categories/*", "themes/*", "house/*"]
}

variable "admin_seed_enabled" {
  description = "Seed an initial admin user on admin-api startup."
  type        = bool
  default     = true
}

variable "admin_seed_username" {
  description = "Initial admin username."
  type        = string
  default     = "admin"
}

variable "admin_seed_password" {
  description = "Initial admin password. If null, Terraform generates one and stores it in SSM."
  type        = string
  default     = null
  sensitive   = true
}

variable "jwt_secret" {
  description = "JWT secret for user-api. If null, Terraform generates one and stores it in SSM."
  type        = string
  default     = null
  sensitive   = true
}

variable "tags" {
  description = "Extra tags applied to all supported resources."
  type        = map(string)
  default     = {}
}
