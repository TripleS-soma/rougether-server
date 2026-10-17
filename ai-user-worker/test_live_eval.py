import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from io import BytesIO

from live_eval import EvaluationHttp
from worker import JsonHttp


class EvaluationBudgetTest(unittest.TestCase):
    def test_restarted_evaluator_counts_shared_calls_including_previous_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            ledger = str(Path(folder) / "calls.jsonl")
            with patch.dict("os.environ", {"AI_USER_CONTROL_URL": "http://127.0.0.1:1", "AI_USER_EVAL_LEDGER": ledger}):
                request = {"model": "test", "messages": [{"content": json.dumps({"state": {"actions": ["WAIT"]}})}]}
                first = EvaluationHttp(str(Path(folder) / "first.jsonl"), 1)
                with patch.object(JsonHttp, "post", side_effect=HTTPError("https://api.anthropic.com/v1/messages",401,"bad key",{},BytesIO(b"{}"))) as send:
                    with self.assertRaises(HTTPError):
                        first.post("https://api.anthropic.com/v1/messages", request, {})
                    self.assertTrue(first.fatal)
                    self.assertEqual(send.call_count, 1)
                restarted = EvaluationHttp(str(Path(folder) / "second.jsonl"), 1)
                with patch.object(JsonHttp, "post") as send:
                    with self.assertRaisesRegex(RuntimeError, "budget exhausted"):
                        restarted.post("https://api.anthropic.com/v1/messages", request, {})
                    send.assert_not_called()


if __name__ == "__main__":
    unittest.main()
