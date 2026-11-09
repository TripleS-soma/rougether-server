"""네트워크와 실제 환경 파일 없이 두 Christmas flag의 파일 변경을 검증함."""
import importlib.util
import io
import json
import os
import stat
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "configure-christmas-flags.py"
SPEC = importlib.util.spec_from_file_location("christmas_flags", SCRIPT)
flags = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = flags
SPEC.loader.exec_module(flags)
EVENT = b"CHRISTMAS_EVENT_ENABLED"
COIN = b"CHRISTMAS_COIN_BONUS_ENABLED"


class FlagFileTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.path = Path(self.folder.name) / "user-api.env"
        self.original = b"SECRET=do-not-print\r\n" + EVENT + b"=false\r\nOTHER=opaque-\xff\r\n" + COIN + b"=false\r\nFINAL=no-newline"
        self.path.write_bytes(self.original)
        self.path.chmod(0o640)

    def backups(self):
        return sorted(self.path.parent.glob("user-api.env.before-christmas*"))

    def test_enable_preserves_other_bytes_crlf_mode_and_owner(self):
        before = self.path.stat()
        result = flags.configure_flags(self.path, True)
        expected = self.original.replace(EVENT + b"=false", EVENT + b"=true").replace(COIN + b"=false", COIN + b"=true")
        self.assertEqual(self.path.read_bytes(), expected)
        after = self.path.stat()
        self.assertEqual(stat.S_IMODE(after.st_mode), 0o640)
        self.assertEqual((before.st_uid, before.st_gid), (after.st_uid, after.st_gid))
        self.assertEqual(self.backups()[0].read_bytes(), self.original)
        self.assertEqual(stat.S_IMODE(self.backups()[0].stat().st_mode), 0o600)
        self.assertTrue(result["changed"])
        self.assertFalse(result["restartPerformed"])
        self.assertNotIn("do-not-print", json.dumps(result))

    def test_enable_already_enabled_is_inode_and_bytes_noop(self):
        flags.configure_flags(self.path, True)
        before = self.path.stat()
        content = self.path.read_bytes()
        backups = self.backups()
        result = flags.configure_flags(self.path, True)
        self.assertFalse(result["changed"])
        self.assertEqual(self.path.stat().st_ino, before.st_ino)
        self.assertEqual(self.path.stat().st_mtime_ns, before.st_mtime_ns)
        self.assertEqual(self.path.read_bytes(), content)
        self.assertEqual(self.backups(), backups)

    def test_check_is_read_only(self):
        before = self.path.stat()
        result = flags.configure_flags(self.path)
        self.assertFalse(result["changed"])
        self.assertEqual(self.path.read_bytes(), self.original)
        self.assertEqual(self.path.stat().st_ino, before.st_ino)
        self.assertEqual(self.backups(), [])
        self.assertEqual(list(result["flags"].values()), [False, False])

    def test_missing_false_disable_is_semantic_noop_without_backup(self):
        content = b"SECRET=still-private\r\nTAIL=no-newline"
        self.path.write_bytes(content)
        before = self.path.stat()
        result = flags.configure_flags(self.path, False)
        self.assertFalse(result["changed"])
        self.assertEqual(self.path.read_bytes(), content)
        self.assertEqual(self.path.stat().st_ino, before.st_ino)
        self.assertEqual(list(result["flags"].values()), [False, False])
        self.assertEqual(list(result["flagsExplicitlyPresentBefore"].values()), [False, False])
        self.assertEqual(self.backups(), [])

    def test_missing_enable_appends_only_two_flags_using_existing_line_endings(self):
        content = b"SECRET=still-private\r\nTAIL=no-newline"
        self.path.write_bytes(content)
        flags.configure_flags(self.path, True)
        self.assertEqual(self.path.read_bytes(), content + b"\r\n" + EVENT + b"=true\r\n" + COIN + b"=true\r\n")

    def test_missing_second_flag_disable_after_true_materializes_both_false(self):
        self.path.write_bytes(b"SECRET=x\n" + EVENT + b"=true")
        flags.configure_flags(self.path, False)
        self.assertEqual(self.path.read_bytes(), b"SECRET=x\n" + EVENT + b"=false\n" + COIN + b"=false\n")

    def test_disable_preserves_first_backup_and_creates_new_private_backup(self):
        flags.configure_flags(self.path, True)
        enabled = self.path.read_bytes()
        first = self.backups()[0]
        result = flags.configure_flags(self.path, False)
        self.assertEqual(self.path.read_bytes(), self.original)
        self.assertEqual(first.read_bytes(), self.original)
        self.assertEqual(len(self.backups()), 2)
        self.assertIn(enabled, [p.read_bytes() for p in self.backups()])
        self.assertTrue(all(stat.S_IMODE(p.stat().st_mode) == 0o600 for p in self.backups()))
        self.assertTrue(result["backupCreated"])

    def test_identical_existing_private_backup_is_reused(self):
        backup = self.path.with_name(self.path.name + ".before-christmas")
        backup.write_bytes(self.original)
        backup.chmod(0o600)
        inode = backup.stat().st_ino
        result = flags.configure_flags(self.path, True)
        self.assertEqual(backup.stat().st_ino, inode)
        self.assertFalse(result["backupCreated"])
        self.assertEqual(len(self.backups()), 1)

    def test_duplicate_or_ambiguous_flag_declarations_refused(self):
        for extra in (EVENT + b"=true\n", b" " + EVENT + b"=true\n", b"export " + COIN + b"=true\n"):
            self.path.write_bytes(self.original + b"\n" + extra)
            content = self.path.read_bytes()
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                flags.configure_flags(self.path, True)
            self.assertEqual(self.path.read_bytes(), content)
            self.assertEqual(self.backups(), [])

    def test_single_noncanonical_flag_refused_instead_of_silently_duplicating(self):
        for declaration in (b" " + EVENT + b"=false\n", b"export " + EVENT + b"=false\n", EVENT + b" =false\n"):
            self.path.write_bytes(declaration)
            with self.subTest(declaration=declaration), self.assertRaises(ValueError):
                flags.configure_flags(self.path, True)

    def test_invalid_flag_value_never_leaks_value(self):
        self.path.write_bytes(EVENT + b"=do-not-print")
        with self.assertRaises(ValueError) as error:
            flags.configure_flags(self.path, True)
        self.assertNotIn("do-not-print", str(error.exception))
        self.assertEqual(self.backups(), [])

    def test_invalid_desired_non_bool_refused(self):
        with self.assertRaises(ValueError):
            flags.configure_flags(self.path, 1)
        self.assertEqual(self.path.read_bytes(), self.original)

    def test_source_symlink_and_hardlink_refused(self):
        linked = self.path.parent / "linked.env"
        linked.symlink_to(self.path)
        with self.assertRaises((ValueError, OSError)):
            flags.configure_flags(linked, True)
        linked.unlink()
        os.link(self.path, linked)
        with self.assertRaises(ValueError):
            flags.configure_flags(self.path, True)
        self.assertEqual(self.path.read_bytes(), self.original)

    def test_insecure_source_permissions_refused(self):
        self.path.chmod(0o666)
        with self.assertRaises(ValueError):
            flags.configure_flags(self.path, True)
        self.assertEqual(self.path.read_bytes(), self.original)

    def test_backup_symlink_is_not_followed(self):
        target = self.path.parent / "unrelated"
        target.write_bytes(b"keep")
        backup = self.path.with_name(self.path.name + ".before-christmas")
        backup.symlink_to(target)
        with self.assertRaises((ValueError, OSError)):
            flags.configure_flags(self.path, True)
        self.assertEqual(target.read_bytes(), b"keep")
        self.assertEqual(self.path.read_bytes(), self.original)

    def test_public_backup_refused_without_overwrite(self):
        backup = self.path.with_name(self.path.name + ".before-christmas")
        backup.write_bytes(self.original)
        backup.chmod(0o644)
        with self.assertRaises(ValueError):
            flags.configure_flags(self.path, True)
        self.assertEqual(backup.read_bytes(), self.original)
        self.assertEqual(self.path.read_bytes(), self.original)

    def test_nonowner_nonroot_refused(self):
        with patch.object(flags.os, "geteuid", return_value=self.path.stat().st_uid + 10000):
            with self.assertRaises(PermissionError):
                flags.configure_flags(self.path, True)
        self.assertEqual(self.path.read_bytes(), self.original)

    def test_wrong_backup_owner_refused(self):
        backup = self.path.with_name(self.path.name + ".before-christmas")
        backup.write_bytes(self.original)
        backup.chmod(0o600)
        source_stat, backup_stat = self.path.stat(), backup.stat()
        fields = ("st_mode", "st_nlink", "st_uid", "st_gid", "st_dev", "st_ino", "st_size", "st_mtime_ns", "st_ctime_ns")
        wrong = SimpleNamespace(**{k: getattr(backup_stat, k) for k in fields})
        wrong.st_uid += 1
        with patch.object(flags.os, "fstat", side_effect=[source_stat, wrong]):
            with self.assertRaises(ValueError):
                flags.configure_flags(self.path, True)
        self.assertEqual(self.path.read_bytes(), self.original)

    def test_replace_failure_keeps_source_and_removes_pending_file(self):
        with patch.object(flags.os, "replace", side_effect=OSError("mock failure")):
            with self.assertRaises(OSError):
                flags.configure_flags(self.path, True)
        self.assertEqual(self.path.read_bytes(), self.original)
        self.assertEqual(list(self.path.parent.glob(".christmas-env-*")), [])
        self.assertEqual(self.backups()[0].read_bytes(), self.original)

    def test_concurrent_change_detected_without_overwriting_other_edit(self):
        original_mkstemp = flags.tempfile.mkstemp
        def edited(*args, **kwargs):
            result = original_mkstemp(*args, **kwargs)
            self.path.write_bytes(b"SECRET=concurrent-preserve\n")
            return result
        with patch.object(flags.tempfile, "mkstemp", side_effect=edited):
            with self.assertRaises(ValueError):
                flags.configure_flags(self.path, True)
        self.assertEqual(self.path.read_bytes(), b"SECRET=concurrent-preserve\n")
        self.assertEqual(list(self.path.parent.glob(".christmas-env-*")), [])


class RemoteCommandTests(unittest.TestCase):
    def test_ssm_source_requires_root_and_never_restarts(self):
        calls = []
        def aws(command, **kwargs):
            calls.append(command)
            if "describe-instances" in command:
                return json.dumps({"Reservations": [{"Instances": [{"State": {"Name": "running"}, "Tags": [{"Key": "Name", "Value": "rougether-dev-app"}]}]}]})
            if "send-command" in command:
                return json.dumps({"Command": {"CommandId": "mock-command"}})
            return json.dumps({"Status": "Success", "StandardOutputContent": json.dumps({"changed": False, "flags": {}, "restartPerformed": False})})
        output = io.StringIO()
        with patch.object(sys, "argv", ["flags", "--instance-id", "i-mock"]), patch.object(flags.subprocess, "check_output", side_effect=aws), redirect_stdout(output):
            flags.main()
        send = next(c for c in calls if "send-command" in c)
        parameters = json.loads(send[send.index("--parameters") + 1])
        remote = parameters["commands"][0]
        self.assertIn("if os.geteuid() != 0:", remote)
        self.assertIn("/etc/rougether/user-api.env", remote)
        self.assertNotIn("systemctl", remote)
        self.assertNotIn("docker restart", remote)
        self.assertFalse(json.loads(output.getvalue())["restartPerformed"])

    def test_other_instance_tag_refuses_before_ssm(self):
        response = json.dumps({"Reservations": [{"Instances": [{"State": {"Name": "running"}, "Tags": [{"Key": "Name", "Value": "another-service"}]}]}]})
        with patch.object(sys, "argv", ["flags", "--instance-id", "i-mock"]), patch.object(flags.subprocess, "check_output", return_value=response) as aws:
            with self.assertRaises(ValueError):
                flags.main()
        self.assertEqual(aws.call_count, 1)


if __name__ == "__main__":
    unittest.main()
