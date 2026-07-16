output "ec2_public_ip" {
  description = "Public IP of the EC2 application instance."
  value       = aws_instance.app.public_ip
}

output "user_api_health_url" {
  description = "User API health check URL."
  value       = "http://${aws_instance.app.public_ip}:8080/api/v1/health"
}

output "admin_url" {
  description = "Admin login/upload URL."
  value       = "http://${aws_instance.app.public_ip}:8081/"
}

output "admin_health_url" {
  description = "Admin API health check URL."
  value       = "http://${aws_instance.app.public_ip}:8081/admin/health"
}

output "ecr_registry_server" {
  description = "ECR registry server used by EC2."
  value       = local.ecr_registry_server
}

output "user_api_image" {
  description = "Docker image used by user-api."
  value       = local.user_api_image_value
}

output "admin_api_image" {
  description = "Docker image used by admin-api."
  value       = local.admin_api_image_value
}

output "batch_image" {
  description = "Docker image used by batch."
  value       = local.batch_image_value
}

output "github_actions_deploy_role_arn" {
  description = "IAM role ARN assumed by GitHub Actions OIDC for ECR push and EC2 deploy."
  value       = aws_iam_role.github_actions_deploy.arn
}

output "rds_endpoint" {
  description = "Private RDS endpoint."
  value       = aws_db_instance.mysql.endpoint
}

output "ssm_session_command" {
  description = "Command to open an SSM shell on the EC2 instance."
  value       = "aws ssm start-session --target ${aws_instance.app.id} --region ${var.aws_region}"
}

output "admin_seed_username" {
  description = "Initial admin username."
  value       = var.admin_seed_username
}

output "admin_seed_password_parameter" {
  description = "SSM parameter name containing the initial admin password."
  value       = aws_ssm_parameter.admin_seed_password.name
}

output "firebase_credentials_parameter" {
  description = "SSM parameter name containing the Firebase service account JSON."
  value       = local.firebase_credentials_param
}
