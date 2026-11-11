import importlib.util
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("guard", ROOT / ".claude/hooks/block-production-mutations.py")
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)
class GuardTest(unittest.TestCase):
    def test_manual_mutations_blocked(self):
        for cmd in ["aws --profile x ssm send-command", "python -c \"client.send_command()\"", "ssh ec2 'nginx -s reload'", "sudo systemctl restart nginx", "aws ssm start-session", "python -c 'import paramiko'"]:
            with self.subTest(cmd=cmd): self.assertTrue(guard.blocked(cmd))
    def test_read_only_and_canonical_workflow_allowed(self):
        for cmd in ["gh run view 123", "gh workflow run docker-publish.yml", "curl https://example.com/health", "aws ssm get-command-invocation", "git diff --check"]:
            with self.subTest(cmd=cmd): self.assertFalse(guard.blocked(cmd))
