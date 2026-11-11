terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 6.0" }
  }
}
variable "region" { type = string }
variable "account_id" { type = string }
variable "role_name" { type = string }
variable "bucket_name" { type = string }
provider "aws" {
  region              = var.region
  allowed_account_ids = [var.account_id]
}
data "aws_iam_role" "app" { name = var.role_name }
# 기존 EC2 스택 대신 독립 state로 사진 채팅 prefix만 추가함.
resource "aws_iam_role_policy" "chat_media" {
  name = "rougether-chat-media"
  role = data.aws_iam_role.app.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:GetObject", "s3:DeleteObject", "s3:DeleteObjectVersion"]
        Resource = ["arn:aws:s3:::${var.bucket_name}/private/chat/*"]
      },
      {
        Effect    = "Allow"
        Action    = ["s3:ListBucketVersions"]
        Resource  = ["arn:aws:s3:::${var.bucket_name}"]
        Condition = { StringLike = { "s3:prefix" = "private/chat/*" } }
      }
    ]
  })
}
