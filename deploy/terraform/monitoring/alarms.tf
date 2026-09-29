locals {
  alarm_actions = [aws_sns_topic.alarms.arn]

  service_memory_thresholds_bytes = {
    for service, limit_mib in var.service_memory_limits_mib :
    service => floor(limit_mib * 1024 * 1024 * var.service_memory_alarm_ratio)
  }
}

# 호스트 메모리 사용률. CloudWatch Agent 가 omit_hostname 으로 차원 없이 보낸다(인스턴스 교체에도 알람 유지).
resource "aws_cloudwatch_metric_alarm" "host_memory_high" {
  alarm_name          = "${var.name}-host-memory-high"
  alarm_description   = "app EC2 호스트 메모리 사용률 ${var.host_memory_alarm_percent}% 이상이 ${var.alarm_evaluation_minutes}분 지속"
  namespace           = var.metric_namespace
  metric_name         = "mem_used_percent"
  statistic           = "Average"
  period              = 60
  evaluation_periods  = var.alarm_evaluation_minutes
  datapoints_to_alarm = var.alarm_evaluation_minutes
  threshold           = var.host_memory_alarm_percent
  comparison_operator = "GreaterThanOrEqualToThreshold"
  # 지표 누락은 host_metrics_missing 알람이 따로 알린다.
  treat_missing_data = "missing"
  alarm_actions      = local.alarm_actions
  ok_actions         = local.alarm_actions
}

# 서비스별 컨테이너 메모리(cgroup working set = memory.current - inactive_file)가 상한의 비율 이상이 지속.
# 배포 스크립트의 수집기가 blue/green 슬롯 중 큰 값을 Service 차원 하나로 보낸다.
resource "aws_cloudwatch_metric_alarm" "service_memory_high" {
  for_each = var.service_memory_limits_mib

  alarm_name          = "${var.name}-${each.key}-memory-high"
  alarm_description   = "${each.key} 컨테이너 메모리 working set 이 상한 ${each.value}MiB 의 ${floor(var.service_memory_alarm_ratio * 100)}% 이상이 ${var.alarm_evaluation_minutes}분 지속"
  namespace           = var.metric_namespace
  metric_name         = local.service_memory_metric
  dimensions          = { Service = each.key }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = var.alarm_evaluation_minutes
  datapoints_to_alarm = var.alarm_evaluation_minutes
  threshold           = local.service_memory_thresholds_bytes[each.key]
  comparison_operator = "GreaterThanOrEqualToThreshold"
  # 컨테이너가 내려가 지표가 없으면 OK 로 본다. 중단·재시작 자체는 #418 호스트 감시가 알린다.
  treat_missing_data = "notBreaching"
  alarm_actions      = local.alarm_actions
  ok_actions         = local.alarm_actions
}

# 에이전트 중단·권한 오류·인스턴스 정지 등으로 호스트 지표가 끊기면 알린다(heartbeat).
resource "aws_cloudwatch_metric_alarm" "host_metrics_missing" {
  alarm_name          = "${var.name}-host-metrics-missing"
  alarm_description   = "app EC2 CloudWatch Agent 지표(mem_used_percent)가 ${var.heartbeat_missing_minutes}분 동안 들어오지 않음"
  namespace           = var.metric_namespace
  metric_name         = "mem_used_percent"
  statistic           = "SampleCount"
  period              = 300
  evaluation_periods  = var.heartbeat_missing_minutes / 5
  datapoints_to_alarm = var.heartbeat_missing_minutes / 5
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}
