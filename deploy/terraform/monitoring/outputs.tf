output "alarm_topic_arn" {
  description = "알람 SNS 토픽 ARN"
  value       = aws_sns_topic.alarms.arn
}

output "webex_forwarder_function_name" {
  description = "SNS → Webex 전달 Lambda 이름"
  value       = aws_lambda_function.forwarder.function_name
}

output "alarm_names" {
  description = "set-alarm-state 로 전달 경로를 시험할 때 쓰는 알람 이름"
  value = concat(
    [
      aws_cloudwatch_metric_alarm.host_memory_high.alarm_name,
      aws_cloudwatch_metric_alarm.host_metrics_missing.alarm_name,
      aws_cloudwatch_metric_alarm.service_metrics_missing.alarm_name,
      aws_cloudwatch_metric_alarm.forwarder_errors.alarm_name,
      aws_cloudwatch_metric_alarm.forwarder_dlq_not_empty.alarm_name,
    ],
    [for alarm in aws_cloudwatch_metric_alarm.service_memory_high : alarm.alarm_name],
  )
}

output "service_memory_alarm_thresholds_bytes" {
  description = "서비스별 메모리 알람 임계치(바이트)"
  value       = local.service_memory_thresholds_bytes
}

output "dashboard_url" {
  description = "메모리 대시보드 콘솔 URL"
  value       = "https://${var.aws_region}.console.aws.amazon.com/cloudwatch/home?region=${var.aws_region}#dashboards/dashboard/${aws_cloudwatch_dashboard.memory.dashboard_name}"
}

output "fallback_topic_arn" {
  description = "알림 경로 실패(Lambda 오류·DLQ) 알람용 SNS 토픽 ARN"
  value       = aws_sns_topic.fallback.arn
}

output "forwarder_dlq_url" {
  description = "Webex 전달에 실패한 알람 이벤트를 보관하는 SQS DLQ URL"
  value       = aws_sqs_queue.forwarder_dlq.url
}
