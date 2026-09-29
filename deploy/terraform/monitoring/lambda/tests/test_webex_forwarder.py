"""webex_forwarder 단위 테스트. 네트워크·boto3 는 모두 mock 한다.

실행: python3 -m unittest discover -s deploy/terraform/monitoring/lambda/tests
"""

import io
import json
import logging
import os
import sys
import types
import unittest
import urllib.error
from unittest import mock

LAMBDA_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if LAMBDA_DIR not in sys.path:
    sys.path.insert(0, LAMBDA_DIR)

# 로컬·CI 에 boto3 가 없어도 import 되도록 가짜 모듈을 먼저 넣는다(Lambda 런타임에는 기본 포함).
if "boto3" not in sys.modules:
    sys.modules["boto3"] = types.SimpleNamespace(client=mock.Mock(name="boto3.client"))

import webex_forwarder  # noqa: E402

TOKEN = "secret-bot-token-value"
ROOM = "room-id-value"
ENVIRONMENT = {
    "WEBEX_BOT_TOKEN_PARAMETER": "/rougether-dev/alerts/webex-bot-token",
    "WEBEX_ROOM_ID_PARAMETER": "/rougether-dev/alerts/webex-room-id",
    "ENVIRONMENT_NAME": "dev",
}


def alarm_record(**overrides):
    alarm = {
        "AlarmName": "rougether-dev-user-api-memory-high",
        "AlarmDescription": "user-api 메모리 상한 90%",
        "NewStateValue": "ALARM",
        "OldStateValue": "OK",
        "NewStateReason": "Threshold Crossed: 5 datapoints were greater than the threshold (1207959552.0).",
        "StateChangeTime": "2026-09-29T05:00:00.123+0000",
        "Trigger": {
            "MetricName": "container_memory_working_set",
            "Namespace": "Rougether/Dev",
            "Dimensions": [{"name": "Service", "value": "user-api"}],
        },
    }
    alarm.update(overrides)
    return {"Sns": {"MessageId": "message-1", "Message": json.dumps(alarm), "Timestamp": "2026-09-29T05:00:01.000Z"}}


class FakeResponse:
    status = 200

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


class FakeSsm:
    def __init__(self, values=None, error=None):
        self.values = values if values is not None else {
            ENVIRONMENT["WEBEX_BOT_TOKEN_PARAMETER"]: TOKEN,
            ENVIRONMENT["WEBEX_ROOM_ID_PARAMETER"]: ROOM,
        }
        self.error = error
        self.calls = []

    def get_parameter(self, Name, WithDecryption):
        self.calls.append((Name, WithDecryption))
        if self.error:
            raise self.error
        return {"Parameter": {"Name": Name, "Value": self.values[Name]}}


class WebexForwarderTest(unittest.TestCase):
    def setUp(self):
        self.environment = mock.patch.dict(os.environ, ENVIRONMENT, clear=False)
        self.environment.start()
        self.ssm = FakeSsm()
        self.ssm_patch = mock.patch.object(webex_forwarder, "_ssm", return_value=self.ssm)
        self.ssm_patch.start()
        webex_forwarder._credential_cache.update(token=None, room=None, expires_at=0.0)
        self.requests = []
        self.urlopen_patch = mock.patch.object(
            webex_forwarder.urllib.request, "urlopen", side_effect=self._fake_urlopen
        )
        self.urlopen = self.urlopen_patch.start()
        self.log_stream = io.StringIO()
        self.log_handler = logging.StreamHandler(self.log_stream)
        webex_forwarder.LOGGER.addHandler(self.log_handler)

    def tearDown(self):
        webex_forwarder.LOGGER.removeHandler(self.log_handler)
        self.urlopen_patch.stop()
        self.ssm_patch.stop()
        self.environment.stop()

    def _fake_urlopen(self, request, timeout):
        self.requests.append((request, timeout))
        return FakeResponse()

    def sent_body(self, index=0):
        return json.loads(self.requests[index][0].data.decode("utf-8"))

    def test_alarm_is_forwarded_as_markdown_with_name_state_reason_and_time(self):
        result = webex_forwarder.handler({"Records": [alarm_record()]}, None)

        self.assertEqual({"forwarded": 1}, result)
        request, timeout = self.requests[0]
        self.assertEqual(webex_forwarder.WEBEX_MESSAGES_URL, request.full_url)
        self.assertEqual("POST", request.get_method())
        self.assertEqual(f"Bearer {TOKEN}", request.get_header("Authorization"))
        self.assertLessEqual(timeout, 10)
        body = self.sent_body()
        self.assertEqual(ROOM, body["roomId"])
        markdown = body["markdown"]
        self.assertIn("[dev] CloudWatch ALARM 발생: rougether-dev-user-api-memory-high", markdown)
        self.assertIn("OK → ALARM", markdown)
        self.assertIn("Threshold Crossed", markdown)
        self.assertIn("2026-09-29 14:00:00 KST", markdown)
        self.assertIn("Rougether/Dev/container\\_memory\\_working\\_set (Service=user-api)", markdown)
        self.assertEqual(
            [(ENVIRONMENT["WEBEX_BOT_TOKEN_PARAMETER"], True), (ENVIRONMENT["WEBEX_ROOM_ID_PARAMETER"], True)],
            self.ssm.calls,
        )

    def test_ok_transition_is_labelled_as_recovery(self):
        webex_forwarder.handler(
            {"Records": [alarm_record(NewStateValue="OK", OldStateValue="ALARM")]}, None
        )
        self.assertIn("CloudWatch OK 복구", self.sent_body()["markdown"])

    def test_mentions_and_markdown_are_neutralised(self):
        record = alarm_record(
            AlarmName="evil <@all> `code` **bold**",
            NewStateReason="ping <@personEmail:ceo@example.com> and @all",
        )
        webex_forwarder.handler({"Records": [record]}, None)

        markdown = self.sent_body()["markdown"]
        self.assertNotIn("<@", markdown)
        self.assertNotIn("@all", markdown)
        self.assertNotIn("`", markdown)
        self.assertIn("‹@​all›", markdown)
        self.assertIn("\\*\\*bold\\*\\*", markdown)

    def test_token_and_room_never_appear_in_logs(self):
        webex_forwarder.handler({"Records": [alarm_record()]}, None)
        logs = self.log_stream.getvalue()
        self.assertIn("Webex 전달 완료", logs)
        self.assertNotIn(TOKEN, logs)
        self.assertNotIn(ROOM, logs)

    def test_http_error_is_raised_without_secrets_for_retry(self):
        self.urlopen.side_effect = urllib.error.HTTPError(
            webex_forwarder.WEBEX_MESSAGES_URL, 401, "Unauthorized", {}, None
        )
        with self.assertRaises(webex_forwarder.ForwardingError) as raised:
            webex_forwarder.handler({"Records": [alarm_record()]}, None)
        self.assertIn("HTTP 401", str(raised.exception))
        self.assertNotIn(TOKEN, str(raised.exception))
        self.assertIsNone(raised.exception.__cause__)

    def test_network_error_is_raised_for_retry(self):
        self.urlopen.side_effect = urllib.error.URLError("timed out")
        with self.assertRaises(webex_forwarder.ForwardingError):
            webex_forwarder.handler({"Records": [alarm_record()]}, None)

    def test_ssm_failure_is_raised_without_leaking_details(self):
        self.ssm.error = RuntimeError(f"AccessDenied {TOKEN}")
        with self.assertRaises(webex_forwarder.ForwardingError) as raised:
            webex_forwarder.handler({"Records": [alarm_record()]}, None)
        self.assertNotIn(TOKEN, str(raised.exception))
        self.assertIn("/rougether-dev/alerts/webex-bot-token", str(raised.exception))
        self.assertEqual([], self.requests)

    def test_invalid_parameter_value_is_rejected(self):
        self.ssm.values[ENVIRONMENT["WEBEX_BOT_TOKEN_PARAMETER"]] = "has space"
        with self.assertRaises(webex_forwarder.ForwardingError):
            webex_forwarder.handler({"Records": [alarm_record()]}, None)
        self.assertEqual([], self.requests)

    def test_credentials_are_cached_between_invocations(self):
        webex_forwarder.handler({"Records": [alarm_record()]}, None)
        webex_forwarder.handler({"Records": [alarm_record()]}, None)
        self.assertEqual(2, len(self.ssm.calls))
        self.assertEqual(2, len(self.requests))

    def test_cache_expires_so_rotated_token_is_picked_up(self):
        webex_forwarder.load_credentials(now=1000.0)
        webex_forwarder.load_credentials(now=1000.0 + webex_forwarder.CREDENTIAL_CACHE_SECONDS + 1)
        self.assertEqual(4, len(self.ssm.calls))

    def test_non_alarm_message_is_forwarded_as_plain_text(self):
        record = {"Sns": {"MessageId": "m", "Subject": "test", "Message": "hello <@all>", "Timestamp": "2026-09-29T00:00:00Z"}}
        webex_forwarder.handler({"Records": [record]}, None)
        markdown = self.sent_body()["markdown"]
        self.assertIn("[dev] test", markdown)
        self.assertIn("hello ‹@​all›", markdown)
        self.assertIn("2026-09-29 09:00:00 KST", markdown)

    def test_empty_event_does_not_read_credentials(self):
        self.assertEqual({"forwarded": 0}, webex_forwarder.handler({}, None))
        self.assertEqual([], self.ssm.calls)

    def test_missing_parameter_env_fails_closed(self):
        with mock.patch.dict(os.environ, {"WEBEX_ROOM_ID_PARAMETER": ""}):
            with self.assertRaises(webex_forwarder.ForwardingError):
                webex_forwarder.handler({"Records": [alarm_record()]}, None)

    def test_long_reason_is_truncated(self):
        webex_forwarder.handler({"Records": [alarm_record(NewStateReason="x" * 5000)]}, None)
        markdown = self.sent_body()["markdown"]
        self.assertLess(len(markdown), webex_forwarder.MAX_MESSAGE_LENGTH + 1)
        self.assertIn("…", markdown)

    def test_unparseable_time_is_sanitised(self):
        self.assertEqual("not\\_a\\_time", webex_forwarder.format_time("not_a_time"))
        self.assertEqual("알 수 없음", webex_forwarder.format_time(None))


if __name__ == "__main__":
    unittest.main()
