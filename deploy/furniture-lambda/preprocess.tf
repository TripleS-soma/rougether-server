variable "enable_preprocessing" {
  type    = bool
  default = false
}
locals {
  preprocess_zip = "${path.module}/../../furniture-lambda-preprocess/build/distributions/furniture-lambda-preprocess.zip"
}
resource "aws_sqs_queue" "preprocess_dead" {
  count                     = var.enable_preprocessing ? 1 : 0
  name                      = "${var.name}-preprocess-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
}
resource "aws_sqs_queue" "preprocess" {
  count                      = var.enable_preprocessing ? 1 : 0
  name                       = "${var.name}-preprocess"
  visibility_timeout_seconds = 1080
  message_retention_seconds  = 86400
  sqs_managed_sse_enabled    = true
  redrive_policy             = jsonencode({ deadLetterTargetArn = aws_sqs_queue.preprocess_dead[0].arn, maxReceiveCount = 2 })
}
data "aws_caller_identity" "preprocess" {
  count = var.enable_preprocessing ? 1 : 0
}
resource "aws_sqs_queue_policy" "preprocess_events" {
  count     = var.enable_preprocessing ? 1 : 0
  queue_url = aws_sqs_queue.preprocess[0].url
  policy = jsonencode({ Version = "2012-10-17", Statement = [{
    Effect   = "Allow", Principal = { Service = "s3.amazonaws.com" }, Action = "sqs:SendMessage",
    Resource = aws_sqs_queue.preprocess[0].arn,
    Condition = {
      ArnEquals    = { "aws:SourceArn" = "arn:aws:s3:::${var.asset_bucket}" },
      StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.preprocess[0].account_id }
    }
  }] })
}
resource "aws_sqs_queue_redrive_allow_policy" "preprocess_dead" {
  count                = var.enable_preprocessing ? 1 : 0
  queue_url            = aws_sqs_queue.preprocess_dead[0].url
  redrive_allow_policy = jsonencode({ redrivePermission = "byQueue", sourceQueueArns = [aws_sqs_queue.preprocess[0].arn] })
}
resource "aws_cloudwatch_log_group" "preprocess" {
  count             = var.enable_preprocessing ? 1 : 0
  name              = "/aws/lambda/${var.name}-preprocess"
  retention_in_days = 14
}
resource "aws_iam_role" "preprocess" {
  count              = var.enable_preprocessing ? 1 : 0
  name               = "${var.name}-preprocess"
  assume_role_policy = local.assume_lambda
}
resource "aws_iam_role_policy" "preprocess" {
  count = var.enable_preprocessing ? 1 : 0
  role  = aws_iam_role.preprocess[0].id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"], Resource = "${aws_cloudwatch_log_group.preprocess[0].arn}:*" },
    { Effect = "Allow", Action = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"], Resource = aws_sqs_queue.preprocess[0].arn },
    { Effect = "Allow", Action = "lambda:InvokeFunction", Resource = aws_lambda_function.control.arn },
    # HEAD에서 아직 없는 출력의 404와 권한 오류 403을 구분하는 데 필요함.
    { Effect = "Allow", Action = "s3:ListBucket", Resource = "arn:aws:s3:::${var.asset_bucket}" },
    { Effect = "Allow", Action = ["s3:GetObject", "s3:GetObjectVersion"], Resource = [
      "arn:aws:s3:::${var.asset_bucket}/private/furniture-generation/raw/*",
      "arn:aws:s3:::${var.asset_bucket}/private/furniture-generation/*/source/preprocessed.png"
    ] },
    { Effect = "Allow", Action = "s3:PutObject", Resource = "arn:aws:s3:::${var.asset_bucket}/private/furniture-generation/*/source/preprocessed.png",
    Condition = { StringEquals = { "s3:if-none-match" = "*" } } }
  ] })
}
resource "aws_iam_role_policy" "direct_upload" {
  count = var.enable_preprocessing ? 1 : 0
  name  = "${var.name}-direct-upload"
  role  = var.publisher_role_name
  policy = jsonencode({ Version = "2012-10-17", Statement = [{
    Effect    = "Allow", Action = "s3:PutObject", Resource = "arn:aws:s3:::${var.asset_bucket}/private/furniture-generation/raw/*",
    Condition = { StringEquals = { "s3:if-none-match" = "*" } }
  }] })
}
resource "aws_s3_object" "preprocess_code" {
  count       = var.enable_preprocessing ? 1 : 0
  bucket      = var.artifact_bucket
  key         = var.enable_preprocessing ? "furniture-lambda/preprocess-${filesha256(local.preprocess_zip)}.zip" : null
  source      = local.preprocess_zip
  source_hash = var.enable_preprocessing ? filesha256(local.preprocess_zip) : null
}
resource "aws_lambda_function" "preprocess" {
  count                          = var.enable_preprocessing ? 1 : 0
  function_name                  = "${var.name}-preprocess"
  role                           = aws_iam_role.preprocess[0].arn
  runtime                        = "java25"
  architectures                  = ["arm64"]
  handler                        = "com.triples.rougether.lambdapreprocess.PreprocessHandler"
  s3_bucket                      = aws_s3_object.preprocess_code[0].bucket
  s3_key                         = aws_s3_object.preprocess_code[0].key
  source_code_hash               = var.enable_preprocessing ? filebase64sha256(local.preprocess_zip) : null
  timeout                        = 180
  memory_size                    = 1024
  reserved_concurrent_executions = 2
  environment {
    variables = {
      CONTROL_FUNCTION_NAME   = aws_lambda_function.control.function_name
      ASSET_S3_BUCKET         = var.asset_bucket
      VIPS_NATIVE_PATH        = "/var/task/native/libvips-cpp.so.8.18.6"
      VIPS_CONCURRENCY        = "1"
      AWS_LAMBDA_EXEC_WRAPPER = "/var/task/bootstrap-wrapper"
    }
  }
  depends_on = [aws_iam_role_policy.preprocess]
}
resource "aws_lambda_event_source_mapping" "preprocess" {
  count                   = var.enable_preprocessing ? 1 : 0
  event_source_arn        = aws_sqs_queue.preprocess[0].arn
  function_name           = aws_lambda_function.preprocess[0].arn
  enabled                 = var.enable_trigger
  batch_size              = 1
  function_response_types = ["ReportBatchItemFailures"]
  scaling_config { maximum_concurrency = 2 }
  depends_on = [aws_iam_role_policy.preprocess]
}
resource "aws_cloudwatch_metric_alarm" "preprocess_dead" {
  count               = var.enable_preprocessing ? 1 : 0
  alarm_name          = "${var.name}-preprocess-dead-letter"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = aws_sqs_queue.preprocess_dead[0].name }
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_topic_arn == null ? [] : [var.alarm_topic_arn]
}
resource "aws_cloudwatch_metric_alarm" "preprocess_age" {
  count               = var.enable_preprocessing ? 1 : 0
  alarm_name          = "${var.name}-preprocess-queue-age"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateAgeOfOldestMessage"
  dimensions          = { QueueName = aws_sqs_queue.preprocess[0].name }
  comparison_operator = "GreaterThanThreshold"
  threshold           = 120
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_topic_arn == null ? [] : [var.alarm_topic_arn]
}
# 기존 bucket notification 전체를 이 모듈이 소유하지 않음. 원래 관리 위치에 아래 항목만 병합해야 함.
output "preprocess_notification" {
  value = var.enable_preprocessing ? {
    Id     = "${var.name}-preprocess", QueueArn = aws_sqs_queue.preprocess[0].arn,
    Events = ["s3:ObjectCreated:Put"],
    Filter = { Key = { FilterRules = [{ Name = "prefix", Value = "private/furniture-generation/raw/" }] } }
  } : null
}
