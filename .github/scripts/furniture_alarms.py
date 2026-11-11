"""가구 AI 업무 실패와 기존 런타임 알람을 운영 SNS에 연결한다."""
from rotate_llm_key import aws

PREFIX = "rougether-dev-furniture"
TOPIC = "arn:aws:sns:ap-northeast-2:776158585524:rougether-dev-ops-alarms"
RUNTIME_ALARMS = [PREFIX + suffix for suffix in (
    "-ai_errors", "-ai_throttles", "-control_errors", "-control_throttles", "-dead-letter", "-queue-age")]
ALARM_FIELDS = {
    "AlarmName", "AlarmDescription", "ActionsEnabled", "OKActions", "AlarmActions", "InsufficientDataActions",
    "MetricName", "Namespace", "Statistic", "ExtendedStatistic", "Dimensions", "Period", "Unit",
    "EvaluationPeriods", "DatapointsToAlarm", "Threshold", "ComparisonOperator", "TreatMissingData",
    "EvaluateLowSampleCountPercentile", "Metrics", "ThresholdMetricId",
}


def configure():
    existing = aws("cloudwatch", "describe-alarms", {"AlarmNames": RUNTIME_ALARMS})["MetricAlarms"]
    if {alarm["AlarmName"] for alarm in existing} != set(RUNTIME_ALARMS):
        raise RuntimeError("Expected furniture runtime alarms missing")
    aws("logs", "put-metric-filter", {
        "logGroupName": "/aws/lambda/" + PREFIX + "-ai", "filterName": PREFIX + "-job-failures",
        "filterPattern": '"FURNITURE_JOB_FAILED"',
        "metricTransformations": [{"metricName": PREFIX + "-job-failures", "metricNamespace": "Rougether/Furniture",
                                   "metricValue": "1", "defaultValue": 0, "unit": "Count"}],
    })
    aws("cloudwatch", "put-metric-alarm", {
        "AlarmName": PREFIX + "-job-failures", "AlarmDescription": "가구 AI 비동기 단계 실패. Lambda Errors가 0이어도 감지함.",
        "Namespace": "Rougether/Furniture", "MetricName": PREFIX + "-job-failures", "Statistic": "Sum",
        "Period": 60, "EvaluationPeriods": 1, "Threshold": 1, "ComparisonOperator": "GreaterThanOrEqualToThreshold",
        "TreatMissingData": "notBreaching", "AlarmActions": [TOPIC],
    })
    for alarm in existing:
        payload = {key: value for key, value in alarm.items() if key in ALARM_FIELDS}
        payload["AlarmActions"] = list(dict.fromkeys([*payload.get("AlarmActions", []), TOPIC]))
        aws("cloudwatch", "put-metric-alarm", payload)
