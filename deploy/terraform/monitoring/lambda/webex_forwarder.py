"""CloudWatch 알람 SNS 메시지를 운영 Webex room 으로 전달한다(#419).

- 토큰·room 은 SSM 파라미터에서 읽고 짧게 캐시한다. 둘 다 로그·예외 메시지에 싣지 않는다.
- 알람 이름·상태·사유·시각을 markdown 으로 보낸다. 사용자 입력이 섞일 수 있는 값은
  멘션(<@...>, @all)과 markdown 강조 문자를 무력화한 뒤 넣는다.
- 전송 실패는 예외로 올려 Lambda 비동기 재시도(최대 2회)에 맡긴다. 401/403 이면 토큰 캐시를 비운다.
  재시도까지 실패한 이벤트는 SQS DLQ 로 가고, Errors·DLQ 알람이 fallback 토픽으로 알린다.
"""

from __future__ import annotations

import json
import logging
import os
import re
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

import boto3

LOGGER = logging.getLogger()
LOGGER.setLevel(logging.INFO)

WEBEX_MESSAGES_URL = "https://webexapis.com/v1/messages"
HTTP_TIMEOUT_SECONDS = 10
CREDENTIAL_CACHE_SECONDS = 300
CREDENTIAL_REJECTED_STATUSES = (401, 403)
MAX_FIELD_LENGTH = 1000
MAX_MESSAGE_LENGTH = 6000
KST = timezone(timedelta(hours=9), "KST")

STATE_LABELS = {
    "ALARM": "ALARM 발생",
    "OK": "OK 복구",
    "INSUFFICIENT_DATA": "데이터 부족",
}

_CONTROL_CHARACTERS = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")
_MARKDOWN_SPECIAL = re.compile(r"([\\*_~\[\]|#])")
_ZERO_WIDTH_SPACE = "​"

_ssm_client = None
_credential_cache: dict = {"expires_at": 0.0, "token": None, "room": None}


class ForwardingError(Exception):
    """Webex 전달 실패. 메시지에 토큰·room 을 넣지 않는다."""


def _ssm():
    global _ssm_client
    if _ssm_client is None:
        _ssm_client = boto3.client("ssm")
    return _ssm_client


def _required_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise ForwardingError(f"환경 변수 {name} 가 비어 있습니다")
    return value


def _read_parameter(name: str) -> str:
    try:
        response = _ssm().get_parameter(Name=name, WithDecryption=True)
    except Exception as error:  # boto3 예외 종류와 무관하게 파라미터 이름만 남긴다.
        raise ForwardingError(f"SSM 파라미터 {name} 조회 실패: {type(error).__name__}") from None
    value = response.get("Parameter", {}).get("Value", "").strip()
    if not value or any(character.isspace() for character in value):
        raise ForwardingError(f"SSM 파라미터 {name} 값이 비었거나 형식이 잘못됐습니다")
    return value


def invalidate_credentials() -> None:
    _credential_cache.update(token=None, room=None, expires_at=0.0)


def load_credentials(now: float | None = None) -> tuple[str, str]:
    current = time.time() if now is None else now
    if _credential_cache["token"] and _credential_cache["expires_at"] > current:
        return _credential_cache["token"], _credential_cache["room"]

    token = _read_parameter(_required_env("WEBEX_BOT_TOKEN_PARAMETER"))
    room = _read_parameter(_required_env("WEBEX_ROOM_ID_PARAMETER"))
    _credential_cache.update(token=token, room=room, expires_at=current + CREDENTIAL_CACHE_SECONDS)
    return token, room


def sanitize(value, limit: int = MAX_FIELD_LENGTH) -> str:
    """Webex markdown 에 넣기 전 멘션·강조·제어 문자를 무력화한다."""
    text = "" if value is None else str(value)
    text = _CONTROL_CHARACTERS.sub(" ", text)
    text = text.replace("<", "‹").replace(">", "›")
    text = text.replace("@", "@" + _ZERO_WIDTH_SPACE)
    text = text.replace("`", "'")
    text = _MARKDOWN_SPECIAL.sub(r"\\\1", text)
    text = " ".join(text.split())
    if len(text) > limit:
        text = text[: limit - 1] + "…"
    return text


def format_time(raw) -> str:
    if not raw:
        return "알 수 없음"
    try:
        normalized = str(raw).replace("Z", "+00:00")
        # CloudWatch 는 2026-09-29T05:00:00.123+0000 처럼 콜론 없는 오프셋을 보낸다.
        normalized = re.sub(r"([+-]\d{2})(\d{2})$", r"\1:\2", normalized)
        parsed = datetime.fromisoformat(normalized)
    except ValueError:
        return sanitize(raw, 64)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(KST).strftime("%Y-%m-%d %H:%M:%S KST")


def _dimensions_text(trigger: dict) -> str:
    dimensions = trigger.get("Dimensions") or []
    parts = []
    for dimension in dimensions:
        if isinstance(dimension, dict):
            name = dimension.get("name") or dimension.get("Name")
            value = dimension.get("value") or dimension.get("Value")
            parts.append(f"{sanitize(name, 64)}={sanitize(value, 128)}")
    return ", ".join(parts) if parts else "없음"


def format_alarm_message(alarm: dict, environment: str) -> str:
    state = str(alarm.get("NewStateValue") or "UNKNOWN")
    state_label = STATE_LABELS.get(state, sanitize(state, 32))
    previous = sanitize(alarm.get("OldStateValue") or "-", 32)
    trigger = alarm.get("Trigger") if isinstance(alarm.get("Trigger"), dict) else {}
    metric = "/".join(
        part for part in (sanitize(trigger.get("Namespace"), 128), sanitize(trigger.get("MetricName"), 128)) if part
    )
    lines = [
        f"**[{sanitize(environment, 32)}] CloudWatch {state_label}: {sanitize(alarm.get('AlarmName'), 256)}**",
        "",
        f"- 상태: {previous} → {sanitize(state, 32)}",
        f"- 사유: {sanitize(alarm.get('NewStateReason'))}",
        f"- 시각: {format_time(alarm.get('StateChangeTime'))}",
    ]
    if metric:
        lines.append(f"- 지표: {metric} ({_dimensions_text(trigger)})")
    description = sanitize(alarm.get("AlarmDescription"))
    if description:
        lines.append(f"- 설명: {description}")
    return "\n".join(lines)[:MAX_MESSAGE_LENGTH]


def format_record(record: dict, environment: str) -> str:
    sns = record.get("Sns") or {}
    raw_message = sns.get("Message", "")
    try:
        alarm = json.loads(raw_message)
    except (TypeError, ValueError):
        alarm = None
    if isinstance(alarm, dict) and "AlarmName" in alarm:
        return format_alarm_message(alarm, environment)

    subject = sanitize(sns.get("Subject") or "SNS 알림", 256)
    return (
        f"**[{sanitize(environment, 32)}] {subject}**\n\n"
        f"- 내용: {sanitize(raw_message)}\n"
        f"- 시각: {format_time(sns.get('Timestamp'))}"
    )


def send_to_webex(markdown: str, token: str, room: str) -> int:
    body = json.dumps({"roomId": room, "markdown": markdown}).encode("utf-8")
    request = urllib.request.Request(
        WEBEX_MESSAGES_URL,
        data=body,
        method="POST",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=HTTP_TIMEOUT_SECONDS) as response:
            return response.status
    except urllib.error.HTTPError as error:
        if error.code in CREDENTIAL_REJECTED_STATUSES:
            # 토큰이 교체·폐기됐을 수 있다. 캐시를 비워 재시도가 SSM 의 새 값을 읽게 한다.
            invalidate_credentials()
        raise ForwardingError(f"Webex 응답 HTTP {error.code}") from None
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise ForwardingError(f"Webex 연결 실패: {type(error).__name__}") from None


def handler(event, context):
    environment = os.environ.get("ENVIRONMENT_NAME", "dev")
    records = (event or {}).get("Records") or []
    if not records:
        LOGGER.info("전달할 SNS 레코드가 없습니다")
        return {"forwarded": 0}

    token, room = load_credentials()
    forwarded = 0
    for record in records:
        markdown = format_record(record, environment)
        status = send_to_webex(markdown, token, room)
        forwarded += 1
        message_id = (record.get("Sns") or {}).get("MessageId", "-")
        LOGGER.info("Webex 전달 완료 messageId=%s status=%s", message_id, status)
    return {"forwarded": forwarded}
