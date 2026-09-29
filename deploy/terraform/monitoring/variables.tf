variable "aws_region" {
  description = "AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "name" {
  description = "리소스 이름 접두어"
  type        = string
  default     = "rougether-dev"
}

variable "environment_name" {
  description = "알림 메시지·태그에 쓰는 환경 이름"
  type        = string
  default     = "dev"
}

variable "ec2_role_name" {
  description = "CloudWatch Agent 권한을 붙일 기존 app EC2 역할. 역할 자체는 메인 스택 소유이며 여기서는 참조만 한다."
  type        = string
  default     = "rougether-dev-ec2-role"
}

variable "metric_namespace" {
  description = "에이전트·서비스 메모리 지표 네임스페이스. 배포 스크립트의 ROUGETHER_CW_NAMESPACE 기본값과 같아야 한다."
  type        = string
  default     = "Rougether/Dev"
}

variable "service_memory_limits_mib" {
  description = "서비스별 컨테이너 메모리 상한(MiB). 배포 스크립트의 ROUGETHER_*_MEMORY_LIMIT 기본값(#418)과 맞춘다."
  type        = map(number)
  default = {
    "user-api"  = 1280
    "admin-api" = 768
    "batch"     = 768
  }

  validation {
    condition = (
      length(setsubtract(keys(var.service_memory_limits_mib), ["user-api", "admin-api", "batch"])) == 0
      && alltrue([for limit in values(var.service_memory_limits_mib) : limit >= 128 && floor(limit) == limit])
    )
    error_message = "서비스 키는 user-api, admin-api, batch 중에서만 쓰고, 상한은 128 이상의 정수 MiB 여야 합니다."
  }
}

variable "service_memory_alarm_ratio" {
  description = "서비스 메모리 알람 임계치(상한 대비 비율)"
  type        = number
  default     = 0.9

  validation {
    condition     = var.service_memory_alarm_ratio > 0 && var.service_memory_alarm_ratio < 1
    error_message = "비율은 0 과 1 사이여야 합니다."
  }
}

variable "host_memory_alarm_percent" {
  description = "호스트 mem_used_percent 알람 임계치(%)"
  type        = number
  default     = 90
}

variable "alarm_evaluation_minutes" {
  description = "메모리 알람이 ALARM 으로 바뀌기까지 연속으로 임계치를 넘어야 하는 1분 구간 수"
  type        = number
  default     = 5
}

variable "heartbeat_missing_minutes" {
  description = "호스트 지표가 이 시간(분) 동안 들어오지 않으면 지표 누락 알람을 낸다. 5 의 배수."
  type        = number
  default     = 10

  validation {
    condition     = var.heartbeat_missing_minutes >= 5 && var.heartbeat_missing_minutes % 5 == 0
    error_message = "5 이상의 5 의 배수여야 합니다."
  }
}

variable "webex_bot_token_parameter" {
  description = "운영 Webex bot 토큰 SSM SecureString 이름(docker-publish.yml 의 WEBEX_BOT_TOKEN_PARAMETER)"
  type        = string
  default     = "/rougether-dev/alerts/webex-bot-token"
}

variable "webex_room_id_parameter" {
  description = "운영 Webex room ID SSM 파라미터 이름(docker-publish.yml 의 WEBEX_ROOM_ID_PARAMETER)"
  type        = string
  default     = "/rougether-dev/alerts/webex-room-id"
}

variable "lambda_runtime" {
  description = "Webex 전달 Lambda 런타임"
  type        = string
  default     = "python3.14"
}

variable "log_retention_days" {
  description = "Webex 전달 Lambda 로그 보존 기간(일)"
  type        = number
  default     = 30
}

variable "fallback_email" {
  description = "알림 경로(Lambda 오류·DLQ) 알람을 받을 이메일. 빈 값이면 fallback 토픽에 구독을 만들지 않는다."
  type        = string
  default     = ""

  validation {
    condition     = var.fallback_email == "" || can(regex("^[^@[:space:]]+@[^@[:space:]]+\\.[^@[:space:]]+$", var.fallback_email))
    error_message = "fallback_email 은 빈 값이거나 이메일 주소여야 합니다."
  }
}
