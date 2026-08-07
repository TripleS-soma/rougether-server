output "ec2_public_ip" {
  description = "Public IP of the EC2 application instance (EIP, stable across stop/start)."
  value       = aws_eip.app.public_ip
}

output "user_api_health_url" {
  description = "User API health check URL (direct HTTP, used by the deploy workflow)."
  value       = "http://${aws_eip.app.public_ip}:8080/api/v1/health"
}

output "user_api_https_base_url" {
  description = "User API HTTPS base URL via CloudFront. Use this as the app's API base URL."
  value       = "https://${aws_cloudfront_distribution.user_api.domain_name}"
}

output "user_api_https_health_url" {
  description = "User API health check URL via CloudFront."
  value       = "https://${aws_cloudfront_distribution.user_api.domain_name}/api/v1/health"
}

output "admin_url" {
  description = "Admin login/upload URL after opening admin_tunnel_command in another terminal."
  value       = "http://127.0.0.1:8081/"
}

output "admin_health_url" {
  description = "Admin API health check URL after opening the SSM tunnel."
  value       = "http://127.0.0.1:8081/admin/health"
}

output "admin_tunnel_command" {
  description = "Encrypted SSM port-forwarding command for the localhost-bound admin API."
  value       = "aws ssm start-session --target ${aws_instance.app.id} --document-name AWS-StartPortForwardingSession --parameters '{\"portNumber\":[\"8081\"],\"localPortNumber\":[\"8081\"]}' --region ${var.aws_region}"
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

output "ec2_instance_id" {
  description = "EC2 instance ID used by SSM and deployment automation."
  value       = aws_instance.app.id
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

output "asset_bucket_name" {
  description = "S3 bucket used for Rougether assets."
  value       = local.asset_bucket_name_value
}

output "asset_public_base_url" {
  description = "Public asset base URL. Terraform-managed buckets use the private S3 CloudFront distribution."
  value       = local.asset_public_base_url_value
}

output "webex_bot_token_parameter" {
  description = "SSM SecureString parameter name containing the Webex operational-alert bot token."
  value       = local.webex_bot_token_param
}

output "webex_room_id_parameter" {
  description = "SSM String parameter name containing the Webex operational-alert room ID."
  value       = local.webex_room_id_param
}

output "social_auth_parameter_names" {
  description = "SSM parameter names for Kakao unlink and Apple token exchange/revoke credentials."
  value = {
    kakao_admin_key             = local.kakao_admin_key_param
    apple_team_id               = local.apple_team_id_param
    apple_key_id                = local.apple_key_id_param
    apple_private_key           = local.apple_private_key_param
    apple_refresh_token_enc_key = local.apple_refresh_token_enc_key_param
  }
}
