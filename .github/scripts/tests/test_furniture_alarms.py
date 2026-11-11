import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("furniture_alarms", SCRIPTS / "furniture_alarms.py")
alarms = importlib.util.module_from_spec(spec)
spec.loader.exec_module(alarms)


class FurnitureAlarmTest(unittest.TestCase):
    def test_preserves_existing_thresholds_and_targets_and_adds_work_failure_signal(self):
        original = [{"AlarmName": name, "AlarmArn": "not-a-request-field", "Period": 60, "Threshold": 120,
                     "EvaluationPeriods": 3, "ActionsEnabled": True, "AlarmActions": ["existing-target"]}
                    for name in alarms.RUNTIME_ALARMS]
        with patch.object(alarms, "aws", side_effect=[{"MetricAlarms": original}] + [{}] * 8) as aws:
            alarms.configure()
        calls = aws.call_args_list
        self.assertEqual(calls[1].args[2]["filterPattern"], '"FURNITURE_JOB_FAILED"')
        self.assertEqual(calls[2].args[2]["AlarmActions"], [alarms.TOPIC])
        for call in calls[3:]:
            value = call.args[2]
            self.assertEqual(value["Threshold"], 120)
            self.assertEqual(value["EvaluationPeriods"], 3)
            self.assertEqual(value["AlarmActions"], ["existing-target", alarms.TOPIC])
            self.assertNotIn("AlarmArn", value)
        self.assertEqual(original[0]["AlarmActions"], ["existing-target"])

    def test_missing_alarm_fails_before_configuration_changes(self):
        with patch.object(alarms, "aws", return_value={"MetricAlarms": []}) as aws:
            with self.assertRaises(RuntimeError): alarms.configure()
        self.assertEqual(aws.call_count, 1)


if __name__ == "__main__": unittest.main()
