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

# 에이전트의 PutMetricData 와 서비스 메모리 수집기(aws cloudwatch put-metric-data) 모두 이 정책으로 동작한다.
# 정책의 ssm:GetParameter(AmazonCloudWatch-*) 는 app 정책의 SSM Deny allowlist 에 막히지만, 에이전트 설정은
# 로컬 파일로 넣으므로 필요 없다.
resource "aws_iam_role_policy_attachment" "cloudwatch_agent" {
  role       = data.aws_iam_role.ec2.name
  policy_arn = "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"
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

# 최소 권한: 두 파라미터 GetParameter 와 자기 로그 그룹 쓰기만 준다.
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
