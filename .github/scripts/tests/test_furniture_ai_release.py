import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("ai_release", SCRIPTS / "furniture_ai_release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class FurnitureAiReleaseTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.package = Path(self.directory.name)
        self.archive = self.package / "furniture-lambda-ai.zip"
        self.archive.write_bytes(b"tested AI package")
        self.digest = hashlib.sha256(self.archive.read_bytes()).hexdigest()
        self.expected = base64.b64encode(bytes.fromhex(self.digest)).decode()
        self.identity = {"source_tree": "a" * 40, "run_id": 10, "run_attempt": 2}
        self.manifest = {"schema": 1, **self.identity, "zip_sha256": self.digest}
        (self.package / "manifest.json").write_text(json.dumps(self.manifest))
        self.before = {"State": "Active", "LastUpdateStatus": "Successful",
                       "RevisionId": "original", "CodeSha256": "old"}
        self.after = {**self.before, "RevisionId": "new", "CodeSha256": self.expected}
        self.current = {"Configuration": self.before, "Code": {"Location": "https://code.example.amazonaws.com/old"}}
        self.previous = {"S3Bucket": release.BUCKET, "S3Key": "furniture-lambda/ai-old.zip", "S3ObjectVersion": "previous"}
        self.proposed = {"S3Bucket": release.BUCKET, "S3Key": "furniture-lambda/ai-new.zip", "S3ObjectVersion": "next"}
        self.package_patch = patch.object(release, "PACKAGE", self.package)
        self.package_patch.start()
        self.addCleanup(self.package_patch.stop)
        self.identity_patch = patch.object(release, "identity", return_value=self.identity)
        self.identity_patch.start()
        self.addCleanup(self.identity_patch.stop)
        self.alarms_patch = patch.object(release, "configure_alarms")
        self.alarms_patch.start()
        self.addCleanup(self.alarms_patch.stop)

    def test_source_run_attempt_and_archive_hash_are_required_before_aws(self):
        self.assertEqual(release.validated_package(), (self.archive, self.digest))
        for field, wrong in [("source_tree", "b" * 40), ("run_id", 11), ("run_attempt", 3), ("zip_sha256", "0" * 64)]:
            with self.subTest(field=field), patch.object(release, "aws") as aws:
                (self.package / "manifest.json").write_text(json.dumps({**self.manifest, field: wrong}))
                with self.assertRaises(release.ReleaseError): release.deploy()
                aws.assert_not_called()
        (self.package / "manifest.json").write_text(json.dumps(self.manifest))
        self.archive.write_bytes(b"tampered")
        with patch.object(release, "aws") as aws:
            with self.assertRaises(release.ReleaseError): release.deploy()
            aws.assert_not_called()

    def test_deploy_preserves_revision_and_pins_s3_version_and_code_hash(self):
        with patch.object(release, "backup", return_value=self.previous) as backup, \
                patch.object(release, "upload", return_value=self.proposed), \
                patch.object(release, "aws", side_effect=[self.current, {}, {}, self.after]) as aws:
            result = release.deploy()
        self.assertEqual(result["state"], "updated")
        self.assertEqual(result["previousCodeSha256"], "old")
        self.assertEqual(aws.call_args_list[1].args, ("lambda", "update-function-code", {
            "FunctionName": release.FUNCTION, **self.proposed, "RevisionId": "original"}))
        self.assertEqual(aws.call_args_list[2].args[:2], ("lambda", "wait function-updated-v2"))
        self.assertFalse(Path(backup.call_args.args[1]).exists())

    def test_upload_failure_never_changes_lambda(self):
        with patch.object(release, "backup", return_value=self.previous), \
                patch.object(release, "upload", side_effect=release.ReleaseError("upload failed")), \
                patch.object(release, "aws", return_value=self.current) as aws:
            with self.assertRaises(release.ReleaseError): release.deploy()
        self.assertEqual(aws.call_count, 1)

    def test_failed_verification_restores_previous_code_in_canonical_execution(self):
        with patch.object(release, "backup", return_value=self.previous), \
                patch.object(release, "upload", return_value=self.proposed), \
                patch.object(release, "aws", side_effect=[self.current, {}]), \
                patch.object(release, "wait_and_verify", side_effect=release.ReleaseError("hash mismatch")), \
                patch.object(release, "restore") as restore:
            with self.assertRaisesRegex(release.ReleaseError, "previous code preserved/restored"): release.deploy()
        restore.assert_called_once_with(self.before, self.previous, self.expected)

    def test_restore_refuses_concurrent_code_change_and_uses_latest_revision(self):
        with patch.object(release, "aws", return_value={**self.after, "CodeSha256": "unrelated"}) as aws:
            with self.assertRaisesRegex(release.ReleaseError, "Concurrent"): release.restore(self.before, self.previous, self.expected)
            self.assertEqual(aws.call_count, 1)
        with patch.object(release, "aws", side_effect=[self.after, {}]) as aws, \
                patch.object(release, "wait_and_verify") as verify:
            release.restore(self.before, self.previous, self.expected)
        self.assertEqual(aws.call_args_list[1].args[2]["RevisionId"], "new")
        verify.assert_called_once_with("old")

    def test_uploaded_checksum_and_version_are_checked(self):
        response = subprocess.CompletedProcess([], 0, json.dumps({"ChecksumSHA256": self.expected, "VersionId": "v1"}), "")
        with patch.object(release.subprocess, "run", return_value=response):
            result = release.upload(self.archive, self.digest)
        self.assertEqual(result["S3ObjectVersion"], "v1")
        self.assertEqual(result["S3Key"], "furniture-lambda/ai-" + self.digest + ".zip")
        with patch.object(release.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, '{}', '')):
            with self.assertRaises(release.ReleaseError): release.upload(self.archive, self.digest)

    def test_backup_download_source_must_be_aws(self):
        with patch.object(release, "urlopen") as open_url:
            with self.assertRaises(release.ReleaseError):
                release.backup({"Code": {"Location": "https://external.example/code"}}, self.directory.name)
            open_url.assert_not_called()

    def test_post_update_hash_mismatch_is_failure(self):
        with patch.object(release, "aws", side_effect=[{}, self.before]):
            with self.assertRaises(release.ReleaseError): release.wait_and_verify(self.expected)


if __name__ == "__main__": unittest.main()
