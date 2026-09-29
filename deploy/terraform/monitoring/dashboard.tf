locals {
  dashboard_services = ["user-api", "admin-api", "batch"]

  host_memory_widget = {
    type   = "metric"
    x      = 0
    y      = 0
    width  = 12
    height = 6
    properties = {
      title   = "호스트 메모리·스왑 사용률"
      region  = var.aws_region
      view    = "timeSeries"
      stacked = false
      period  = 60
      stat    = "Average"
      metrics = [
        [var.metric_namespace, "mem_used_percent", { label = "mem_used_percent" }],
        [var.metric_namespace, "swap_used_percent", { label = "swap_used_percent" }],
      ]
      yAxis = { left = { min = 0, max = 100, label = "%", showUnits = false } }
      annotations = {
        horizontal = [{ label = "알람 ${var.host_memory_alarm_percent}%", value = var.host_memory_alarm_percent, color = "#d62728" }]
      }
    }
  }

  alarm_status_widget = {
    type   = "alarm"
    x      = 12
    y      = 0
    width  = 12
    height = 6
    properties = {
      title = "메모리 알람 상태"
      alarms = concat(
        [aws_cloudwatch_metric_alarm.host_memory_high.arn, aws_cloudwatch_metric_alarm.host_metrics_missing.arn],
        [for service in local.dashboard_services : aws_cloudwatch_metric_alarm.service_memory_high[service].arn if contains(keys(var.service_memory_limits_mib), service)],
      )
    }
  }

  service_memory_widgets = [
    for index, service in [for service in local.dashboard_services : service if contains(keys(var.service_memory_limits_mib), service)] : {
      type   = "metric"
      x      = (index % 3) * 8
      y      = 6
      width  = 8
      height = 6
      properties = {
        title   = "${service} 메모리(working set, MiB)"
        region  = var.aws_region
        view    = "timeSeries"
        stacked = false
        period  = 60
        metrics = [
          [{ expression = "m1 / 1048576", label = "${service} working set", id = "e1" }],
          [var.metric_namespace, local.service_memory_metric, "Service", service, { id = "m1", stat = "Maximum", visible = false }],
        ]
        yAxis = { left = { min = 0, max = ceil(var.service_memory_limits_mib[service] * 1.1), label = "MiB", showUnits = false } }
        annotations = {
          horizontal = [
            { label = "상한 ${var.service_memory_limits_mib[service]}MiB", value = var.service_memory_limits_mib[service], color = "#d62728" },
            { label = "알람 ${floor(var.service_memory_alarm_ratio * 100)}%", value = var.service_memory_limits_mib[service] * var.service_memory_alarm_ratio, color = "#ff7f0e" },
          ]
        }
      }
    }
  ]
}

resource "aws_cloudwatch_dashboard" "memory" {
  dashboard_name = "${var.name}-memory"
  dashboard_body = jsonencode({
    widgets = concat([local.host_memory_widget, local.alarm_status_widget], local.service_memory_widgets)
  })
}
