import copy
import importlib.util
from pathlib import Path
import unittest
spec = importlib.util.spec_from_file_location("merge_notification", Path(__file__).parents[1] / "merge-preprocess-notification.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
ENTRY = {"Id": "furniture-preprocess", "QueueArn": "arn:aws:sqs:ap-northeast-2:000000000000:preprocess", "Events": ["s3:ObjectCreated:Put"], "Filter": {"Key": {"FilterRules": [{"Name": "prefix", "Value": "private/furniture-generation/raw/"}]}}}
class NotificationMergeTest(unittest.TestCase):
    def test_preserves_all_existing_destinations_and_does_not_modify_input(self):
        original = {"EventBridgeConfiguration": {}, "TopicConfigurations": [{"Id": "topic", "TopicArn": "topic", "Events": ["s3:ObjectRemoved:*"]}], "QueueConfigurations": [{"Id": "queue", "QueueArn": "old", "Events": ["s3:ObjectCreated:Put"], "Filter": {"Key": {"FilterRules": [{"Name": "prefix", "Value": "profile/"}]}}}], "LambdaFunctionConfigurations": [{"Id": "lambda", "LambdaFunctionArn": "old", "Events": ["s3:ObjectCreated:Post"]}]}
        snapshot = copy.deepcopy(original)
        result = module.merge(original, ENTRY)
        self.assertEqual(original, snapshot)
        self.assertEqual(result["EventBridgeConfiguration"], {})
        self.assertEqual(result["TopicConfigurations"], original["TopicConfigurations"])
        self.assertEqual(result["LambdaFunctionConfigurations"], original["LambdaFunctionConfigurations"])
        self.assertEqual(result["QueueConfigurations"], original["QueueConfigurations"] + [ENTRY])
    def test_duplicate_is_idempotent(self):
        result = module.merge({}, ENTRY)
        self.assertEqual(module.merge(result, ENTRY), result)
    def test_same_id_cannot_overwrite_different_destination(self):
        changed = dict(ENTRY, QueueArn="arn:aws:sqs:ap-northeast-2:000000000000:other")
        with self.assertRaises(ValueError): module.merge({"QueueConfigurations": [changed]}, ENTRY)
    def test_overlapping_object_created_routes_fail_closed(self):
        for events, prefix in [(["s3:ObjectCreated:*"], ""), (["s3:ObjectCreated:Put"], "private/"), (["s3:ObjectCreated:Put"], "private/furniture-generation/raw/a")]:
            old = {"Id": "other", "Events": events, "Filter": {"Key": {"FilterRules": [{"Name": "prefix", "Value": prefix}]}}}
            with self.subTest(prefix=prefix), self.assertRaises(ValueError): module.merge({"TopicConfigurations": [old]}, ENTRY)
    def test_rejects_unreviewed_entry_and_unknown_response_fields(self):
        with self.assertRaises(ValueError): module.merge({}, dict(ENTRY, Events=["s3:ObjectCreated:*"]))
        with self.assertRaises(ValueError): module.merge({"ResponseMetadata": {}}, ENTRY)
if __name__ == "__main__": unittest.main()
