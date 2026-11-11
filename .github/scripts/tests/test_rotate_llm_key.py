import importlib.util
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("rotation", Path(__file__).resolve().parents[1] / "rotate_llm_key.py")
rotation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rotation)


class RotationTest(unittest.TestCase):
    def setUp(self):
        self.variables = {"LLM_API_KEY_PARAMETER": rotation.PARAMETER, "OTHER_SETTING": "preserve"}
        self.before = {"State": "Active", "LastUpdateStatus": "Successful", "RevisionId": "original",
                       "Environment": {"Variables": self.variables}}
        self.after = {**self.before, "Environment": {"Variables": {**self.variables, "LLM_API_KEY_VERSION": "2"}}}

    def test_rotation_preserves_settings_and_fences_concurrent_update(self):
        with patch.object(rotation, "aws", side_effect=[self.before, {"Version": 2}, {}, {}, self.after]) as aws:
            result = rotation.rotate("sk-test-only-value")
        self.assertEqual(result["version"], 2)
        calls = aws.call_args_list
        self.assertEqual(calls[1].args, ("ssm", "put-parameter", {
            "Name": rotation.PARAMETER, "Type": "SecureString", "Value": "sk-test-only-value", "Overwrite": True}))
        self.assertEqual(calls[2].args[2], {"FunctionName": rotation.FUNCTION, "RevisionId": "original",
                                         "Environment": self.after["Environment"]})
        self.assertEqual(calls[3].args[:2], ("lambda", "wait function-updated-v2"))
        self.assertNotIn("sk-test-only-value", json.dumps(result))
        self.assertNotIn("LLM_API_KEY_VERSION", self.variables)

    def test_invalid_or_missing_secret_never_calls_aws(self):
        for value in ["", "invalid", "sk-a\n", "sk-" + "a" * 513]:
            with self.subTest(value=value), patch.object(rotation, "aws") as aws:
                with self.assertRaises(rotation.RotationError): rotation.rotate(value)
                aws.assert_not_called()

    def test_not_ready_or_wrong_parameter_never_changes_ssm(self):
        for before in [{**self.before, "LastUpdateStatus": "InProgress"},
                       {**self.before, "Environment": {"Variables": {}}}]:
            with patch.object(rotation, "aws", return_value=before) as aws:
                with self.assertRaises(rotation.RotationError): rotation.rotate("sk-test-only-value")
                self.assertEqual(aws.call_count, 1)

    def test_failed_ssm_write_does_not_refresh_lambda(self):
        with patch.object(rotation, "aws", side_effect=[self.before, rotation.RotationError("write failed")]) as aws:
            with self.assertRaises(rotation.RotationError): rotation.rotate("sk-test-only-value")
            self.assertEqual(aws.call_count, 2)

    def test_configuration_mismatch_is_not_success(self):
        with patch.object(rotation, "aws", side_effect=[self.before, {"Version": 2}, {}, {}, self.before]):
            with self.assertRaises(rotation.RotationError): rotation.rotate("sk-test-only-value")

    def test_aws_error_does_not_expose_secret_and_uses_stdin(self):
        response = subprocess.CompletedProcess([], 1, "", "request contained sk-test-only-value")
        with patch.object(rotation.subprocess, "run", return_value=response) as run:
            with self.assertRaisesRegex(rotation.RotationError, "ssm:put-parameter failed") as error:
                rotation.aws("ssm", "put-parameter", {"Value": "sk-test-only-value"})
        self.assertNotIn("sk-test-only-value", str(error.exception))
        self.assertNotIn("sk-test-only-value", " ".join(run.call_args.args[0]))
        self.assertEqual(json.loads(run.call_args.kwargs["input"])["Value"], "sk-test-only-value")


if __name__ == "__main__": unittest.main()
