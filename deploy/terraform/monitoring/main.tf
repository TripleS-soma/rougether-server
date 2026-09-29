data "aws_caller_identity" "current" {}

# 역할은 메인 스택(../ec2) 소유다. 여기서는 존재만 확인하고 관리형 정책 연결 하나만 관리한다.
data "aws_iam_role" "ec2" {
  name = var.ec2_role_name
}

locals {
  forwarder_name = "${var.name}-alarm-webex"
  # 배포 스크립트의 서비스 메모리 수집기가 보내는 지표 이름·차원(Service=<서비스>)과 정확히 같아야 한다.
  service_memory_metric = "container_memory_working_set"
  webex_parameter_arns = [
    for parameter in [var.webex_bot_token_parameter, var.webex_room_id_parameter] :
    "arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter/${trimprefix(parameter, "/")}"
  ]
}

# 에이전트와 서비스 메모리 수집기(aws cloudwatch put-metric-data)가 쓰는 권한만 준다.
# 관리형 CloudWatchAgentServerPolicy 는 logs·ec2:DescribeTags/Volumes·ssm 까지 열어 대신 인라인 최소 정책을 쓴다.
# 에이전트 설정(배포 스크립트)은 logs 섹션·append_dimensions·ec2 태그·디스크 지표가 없어 PutMetricData 외 API 를
# 호출하지 않는다(인스턴스 ID·리전은 IMDS 에서 읽는다). 설정에 그런 항목을 추가하면 여기 권한도 같이 늘린다.
# PutMetricData 는 리소스 단위 권한이 없어 Resource "*" 에 네임스페이스 조건으로 좁힌다.
# 메인 스택의 aws_iam_role.ec2 는 inline_policy 를 독점 관리하지 않으므로 메인 스택 apply 가 이 정책을 지우지 않는다.
resource "aws_iam_role_policy" "ec2_cloudwatch_metrics" {
  name = "${var.name}-cloudwatch-metrics"
  role = data.aws_iam_role.ec2.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["cloudwatch:PutMetricData"]
      Resource = "*"
      Condition = {
        StringEquals = { "cloudwatch:namespace" = var.metric_namespace }
      }
    }]
  })
}

# 알람 데이터는 비밀이 아니고, CloudWatch 알람은 AWS 관리형 키(aws/sns)로 암호화된 토픽에 게시할 수 없어 암호화하지 않는다.
resource "aws_sns_topic" "alarms" {
  name = "${var.name}-ops-alarms"
}

data "archive_file" "forwarder" {
  type        = "zip"
  source_file = "${path.module}/lambda/webex_forwarder.py"
  output_path = "${path.module}/.build/webex_forwarder.zip"
}

resource "aws_cloudwatch_log_group" "forwarder" {
  name              = "/aws/lambda/${local.forwarder_name}"
  retention_in_days = var.log_retention_days
}

resource "aws_iam_role" "forwarder" {
  name = "${local.forwarder_name}-lambda"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "lambda.amazonaws.com" }
    }]
  })
}

# 최소 권한: 두 파라미터 GetParameter, 자기 로그 그룹 쓰기, DLQ 전송만 준다.
# 토큰은 AWS 관리형 키(alias/aws/ssm)로 암호화돼 있고, 그 키 정책이 같은 계정 주체의 SSM 경유 복호화를
# 허용하므로 kms:Decrypt 를 따로 주지 않는다. 고객 관리형 키로 바꾸면 kms:Decrypt 를 추가해야 한다.
resource "aws_iam_role_policy" "forwarder" {
  name = "${local.forwarder_name}-runtime"
  role = aws_iam_role.forwarder.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["ssm:GetParameter"]
        Resource = local.webex_parameter_arns
      },
      {
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "${aws_cloudwatch_log_group.forwarder.arn}:*"
      },
      {
        # 비동기 재시도까지 실패한 이벤트를 on-failure destination(DLQ)으로 보낸다.
        Effect   = "Allow"
        Action   = ["sqs:SendMessage"]
        Resource = aws_sqs_queue.forwarder_dlq.arn
      }
    ]
  })
}

resource "aws_lambda_function" "forwarder" {
  function_name    = local.forwarder_name
  description      = "CloudWatch 알람 SNS 메시지를 운영 Webex 로 전달"
  role             = aws_iam_role.forwarder.arn
  runtime          = var.lambda_runtime
  architectures    = ["arm64"]
  handler          = "webex_forwarder.handler"
  filename         = data.archive_file.forwarder.output_path
  source_code_hash = data.archive_file.forwarder.output_base64sha256
  memory_size      = 128
  timeout          = 30

  environment {
    variables = {
      WEBEX_BOT_TOKEN_PARAMETER = var.webex_bot_token_parameter
      WEBEX_ROOM_ID_PARAMETER   = var.webex_room_id_parameter
      ENVIRONMENT_NAME          = var.environment_name
    }
  }

  depends_on = [
    aws_cloudwatch_log_group.forwarder,
    aws_iam_role_policy.forwarder,
  ]
}

resource "aws_lambda_permission" "sns" {
  statement_id  = "AllowAlarmTopicInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.forwarder.function_name
  principal     = "sns.amazonaws.com"
  source_arn    = aws_sns_topic.alarms.arn
}

resource "aws_sns_topic_subscription" "forwarder" {
  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "lambda"
  endpoint  = aws_lambda_function.forwarder.arn

  depends_on = [aws_lambda_permission.sns]
}

# Webex 전달이 재시도(2회)까지 실패한 SNS 이벤트를 보관한다. 원문 알람을 여기서 확인·재처리한다.
resource "aws_sqs_queue" "forwarder_dlq" {
  name                      = "${local.forwarder_name}-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
}

resource "aws_lambda_function_event_invoke_config" "forwarder" {
  function_name                = aws_lambda_function.forwarder.function_name
  maximum_retry_attempts       = 2
  maximum_event_age_in_seconds = 3600

  destination_config {
    on_failure {
      destination = aws_sqs_queue.forwarder_dlq.arn
    }
  }

  depends_on = [aws_iam_role_policy.forwarder]
}

# 알림 경로 자체(Lambda·DLQ)의 알람은 같은 SNS→Lambda 로 보내면 실패가 순환하므로 별도 토픽으로 보낸다.
# 이메일 구독은 fallback_email 을 줄 때만 만들며, 수신자가 확인 메일을 승인해야 전달된다.
resource "aws_sns_topic" "fallback" {
  name = "${var.name}-ops-alarms-fallback"
}

resource "aws_sns_topic_subscription" "fallback_email" {
  count = var.fallback_email == "" ? 0 : 1

  topic_arn = aws_sns_topic.fallback.arn
  protocol  = "email"
  endpoint  = var.fallback_email
}
