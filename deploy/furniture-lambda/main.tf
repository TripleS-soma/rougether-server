terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 6.0" }
  }
}
provider "aws" { region = var.region }
variable "region" { default = "ap-northeast-2" }
variable "name" { default = "rougether-dev-furniture" }
variable "asset_bucket" { type = string }
variable "artifact_bucket" { type = string }
variable "ai_key_parameter_arn" { type = string }
variable "ai_key_parameter_name" { type = string }
variable "ai_key_kms_arn" { type = string }
variable "vpc_id" { type = string }
variable "private_subnet_ids" { type = list(string) }
variable "rds_security_group_id" { type = string }
variable "publisher_role_name" { type = string }
variable "db_url" { type = string }
variable "db_username" { type = string }
variable "db_password" {
  type      = string
  sensitive = true
}
variable "image_model" { default = "gpt-image-2.5-flare" }
variable "style_reference_keys" {
  type    = string
  default = "items/cozy-developer-room/furniture/cozy-developer-room-cozy-chair.png"
}
variable "enable_trigger" { default = false }
variable "alarm_topic_arn" {
  type    = string
  default = null
}
variable "ai_concurrency" {
  type    = number
  default = 2
  validation {
    condition     = var.ai_concurrency >= 2 && var.ai_concurrency <= 100 && floor(var.ai_concurrency) == var.ai_concurrency
    error_message = "AI 동시 실행은 정수 2~100 범위여야 합니다."
  }
}
variable "control_concurrency" {
  type    = number
  default = 2
  validation {
    condition     = var.control_concurrency >= 2 && var.control_concurrency <= 50 && floor(var.control_concurrency) == var.control_concurrency
    error_message = "DB 제어 동시 실행은 연결 예산을 확인한 정수 2~50 범위여야 합니다."
  }
}

locals {
  assume_lambda = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Action = "sts:AssumeRole", Principal = { Service = "lambda.amazonaws.com" } }] })
  ai_zip        = "${path.module}/../../furniture-lambda-ai/build/distributions/furniture-lambda-ai.zip"
  control_zip   = "${path.module}/../../furniture-lambda-control/build/distributions/furniture-lambda-control.zip"
}
resource "aws_sqs_queue" "dead" {
  name                      = "${var.name}-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
}
resource "aws_sqs_queue" "jobs" {
  name                       = "${var.name}-jobs"
  visibility_timeout_seconds = 3600
  message_retention_seconds  = 345600
  sqs_managed_sse_enabled    = true
  redrive_policy             = jsonencode({ deadLetterTargetArn = aws_sqs_queue.dead.arn, maxReceiveCount = 5 })
}
resource "aws_sqs_queue_redrive_allow_policy" "dead" {
  queue_url            = aws_sqs_queue.dead.id
  redrive_allow_policy = jsonencode({ redrivePermission = "byQueue", sourceQueueArns = [aws_sqs_queue.jobs.arn] })
}
resource "aws_cloudwatch_log_group" "ai" {
  name              = "/aws/lambda/${var.name}-ai"
  retention_in_days = 14
}
resource "aws_cloudwatch_log_group" "control" {
  name              = "/aws/lambda/${var.name}-control"
  retention_in_days = 14
}
resource "aws_iam_role" "ai" {
  name               = "${var.name}-ai"
  assume_role_policy = local.assume_lambda
}
resource "aws_iam_role" "control" {
  name               = "${var.name}-control"
  assume_role_policy = local.assume_lambda
}
resource "aws_security_group" "control" {
  name        = "${var.name}-control"
  description = "Furniture DB control Lambda"
  vpc_id      = var.vpc_id
}
resource "aws_vpc_security_group_egress_rule" "database" {
  security_group_id            = aws_security_group.control.id
  referenced_security_group_id = var.rds_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 3306
  to_port                      = 3306
}
resource "aws_vpc_security_group_ingress_rule" "database" {
  security_group_id            = var.rds_security_group_id
  referenced_security_group_id = aws_security_group.control.id
  ip_protocol                  = "tcp"
  from_port                    = 3306
  to_port                      = 3306
}
resource "aws_iam_role_policy" "control" {
  role = aws_iam_role.control.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"], Resource = "${aws_cloudwatch_log_group.control.arn}:*" },
    { Effect = "Allow", Action = ["ec2:CreateNetworkInterface", "ec2:DescribeNetworkInterfaces", "ec2:DescribeSubnets", "ec2:DeleteNetworkInterface", "ec2:AssignPrivateIpAddresses", "ec2:UnassignPrivateIpAddresses"], Resource = "*" }
  ] })
}
resource "aws_s3_object" "ai_code" {
  bucket      = var.artifact_bucket
  key         = "furniture-lambda/ai-${filesha256(local.ai_zip)}.zip"
  source      = local.ai_zip
  source_hash = filesha256(local.ai_zip)
}
resource "aws_s3_object" "control_code" {
  bucket      = var.artifact_bucket
  key         = "furniture-lambda/control-${filesha256(local.control_zip)}.zip"
  source      = local.control_zip
  source_hash = filesha256(local.control_zip)
}
resource "aws_lambda_function" "control" {
  function_name                  = "${var.name}-control"
  role                           = aws_iam_role.control.arn
  runtime                        = "java25"
  architectures                  = ["arm64"]
  handler                        = "com.triples.rougether.lambdacontrol.ControlHandler"
  s3_bucket                      = aws_s3_object.control_code.bucket
  s3_key                         = aws_s3_object.control_code.key
  source_code_hash               = filebase64sha256(local.control_zip)
  timeout                        = 60
  memory_size                    = 1024
  reserved_concurrent_executions = var.control_concurrency
  vpc_config {
    subnet_ids         = var.private_subnet_ids
    security_group_ids = [aws_security_group.control.id]
  }
  environment {
    variables = {
      SPRING_DATASOURCE_URL      = var.db_url
      SPRING_DATASOURCE_USERNAME = var.db_username
      SPRING_DATASOURCE_PASSWORD = var.db_password
      BILLING_REQUIRE_CREDITS    = "true"
    }
  }
  depends_on = [aws_iam_role_policy.control, aws_vpc_security_group_ingress_rule.database]
}
resource "aws_iam_role_policy" "ai" {
  role = aws_iam_role.ai.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"], Resource = "${aws_cloudwatch_log_group.ai.arn}:*" },
    { Effect = "Allow", Action = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"], Resource = aws_sqs_queue.jobs.arn },
    { Effect = "Allow", Action = "lambda:InvokeFunction", Resource = aws_lambda_function.control.arn },
    { Effect = "Allow", Action = "ssm:GetParameter", Resource = var.ai_key_parameter_arn },
    { Effect = "Allow", Action = "kms:Decrypt", Resource = var.ai_key_kms_arn, Condition = { StringEquals = { "kms:ViaService" = "ssm.${var.region}.amazonaws.com" } } },
    { Effect = "Allow", Action = "s3:GetObject", Resource = ["arn:aws:s3:::${var.asset_bucket}/private/furniture-generation/*", "arn:aws:s3:::${var.asset_bucket}/items/*"] },
    { Effect = "Allow", Action = "s3:PutObject", Resource = ["arn:aws:s3:::${var.asset_bucket}/private/furniture-generation/*", "arn:aws:s3:::${var.asset_bucket}/items/photo-furniture/furniture/*"] }
  ] })
}
resource "aws_lambda_function" "ai" {
  function_name                  = "${var.name}-ai"
  role                           = aws_iam_role.ai.arn
  runtime                        = "java25"
  architectures                  = ["arm64"]
  handler                        = "com.triples.rougether.lambdaai.AiHandler"
  s3_bucket                      = aws_s3_object.ai_code.bucket
  s3_key                         = aws_s3_object.ai_code.key
  source_code_hash               = filebase64sha256(local.ai_zip)
  timeout                        = 600
  memory_size                    = 2048
  reserved_concurrent_executions = var.ai_concurrency
  environment {
    variables = {
      CONTROL_FUNCTION_NAME                     = aws_lambda_function.control.function_name
      ASSET_S3_BUCKET                           = var.asset_bucket
      LLM_API_KEY_PARAMETER                     = var.ai_key_parameter_name
      FURNITURE_GENERATION_ENABLED              = "true"
      FURNITURE_GENERATION_IMAGE_MODEL          = var.image_model
      FURNITURE_GENERATION_STYLE_REFERENCE_KEYS = var.style_reference_keys
    }
  }
  depends_on = [aws_iam_role_policy.ai]
}
resource "aws_iam_role_policy" "publisher" {
  name = "${var.name}-publish"
  role = var.publisher_role_name
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Effect = "Allow", Action = "sqs:SendMessage", Resource = aws_sqs_queue.jobs.arn }
  ] })
}
resource "aws_lambda_event_source_mapping" "jobs" {
  event_source_arn        = aws_sqs_queue.jobs.arn
  function_name           = aws_lambda_function.ai.arn
  enabled                 = var.enable_trigger
  batch_size              = 1
  function_response_types = ["ReportBatchItemFailures"]
  scaling_config { maximum_concurrency = var.ai_concurrency }
  depends_on = [aws_iam_role_policy.ai]
}
resource "aws_cloudwatch_metric_alarm" "dlq" {
  alarm_name          = "${var.name}-dead-letter"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = aws_sqs_queue.dead.name }
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_topic_arn == null ? [] : [var.alarm_topic_arn]
}
resource "aws_cloudwatch_metric_alarm" "age" {
  alarm_name          = "${var.name}-queue-age"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateAgeOfOldestMessage"
  dimensions          = { QueueName = aws_sqs_queue.jobs.name }
  comparison_operator = "GreaterThanThreshold"
  threshold           = 120
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_topic_arn == null ? [] : [var.alarm_topic_arn]
}
resource "aws_cloudwatch_metric_alarm" "function_failure" {
  for_each = {
    ai_errors         = { function = aws_lambda_function.ai.function_name, metric = "Errors" }
    ai_throttles      = { function = aws_lambda_function.ai.function_name, metric = "Throttles" }
    control_errors    = { function = aws_lambda_function.control.function_name, metric = "Errors" }
    control_throttles = { function = aws_lambda_function.control.function_name, metric = "Throttles" }
  }
  alarm_name          = "${var.name}-${each.key}"
  namespace           = "AWS/Lambda"
  metric_name         = each.value.metric
  dimensions          = { FunctionName = each.value.function }
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_topic_arn == null ? [] : [var.alarm_topic_arn]
}
output "queue_url" { value = aws_sqs_queue.jobs.url }
output "ai_function" { value = aws_lambda_function.ai.function_name }
output "control_function" { value = aws_lambda_function.control.function_name }
