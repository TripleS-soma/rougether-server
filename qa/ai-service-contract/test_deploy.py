"""배포 실패가 현재 AI 트래픽과 release state를 보존하는지 검사한다."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('ai_deploy', Path(__file__).resolve().parents[2] / 'deploy/ai-service/deploy.py')
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)
IMAGE = '123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/rougether-dev/ai-service@sha256:' + 'a' * 64


class DeployTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        deploy.ROOT = self.root
        deploy.NGINX_CONFIG = self.root / 'nginx.conf'
        deploy.NGINX_CONFIG.write_text('previous working proxy')
        self.previous = {'image': IMAGE, 'port': 18090, 'name': 'rougether-ai-18090'}
        (self.root / 'state.json').write_text(json.dumps(self.previous))
        self.calls = []
        self.env = {'AI_SERVICE_TOKEN': 'test-token' * 5, 'AI_CHAT_MODEL': 'chat', 'AI_EMBEDDING_MODEL': 'embed', 'AI_EMBEDDING_DIMENSIONS': '2'}
        self.runtime = {'private_ip': '10.39.10.50', 'certificate': 'cert', 'private_key': 'key', 'ca_certificate': 'ca', 'env': self.env}
        for target, value in [
            ('parameter', lambda name: json.dumps(self.runtime) if name.endswith('/runtime') else 'provider-key'),
            ('run', lambda *args, **kwargs: self.calls.append(args) or ''),
            ('request', lambda *args, **kwargs: {}),
            ('smoke', lambda *args: None),
        ]:
            patcher = patch.object(deploy, target, side_effect=value)
            setattr(self, target, patcher.start())
            self.addCleanup(patcher.stop)
        for target in ['subprocess.run', 'time.sleep', 'ssl.create_default_context']:
            patcher = patch.object(
                getattr(deploy, target.split('.')[0]), target.split('.')[1])
            patcher.start()
            self.addCleanup(patcher.stop)

    def assert_preserved(self):
        self.assertEqual('previous working proxy', deploy.NGINX_CONFIG.read_text())
        self.assertEqual(self.previous, json.loads((self.root / 'state.json').read_text()))
        self.assertNotIn(('docker', 'stop', 'rougether-ai-18090'), self.calls)

    def test_provider_failure_preserves_previous_release(self):
        self.smoke.side_effect = RuntimeError('provider failure')
        with self.assertRaises(RuntimeError):
            deploy.deploy(IMAGE)
        self.assert_preserved()
        self.assertNotIn(('systemctl', 'reload-or-restart', 'nginx'), self.calls)

    def test_invalid_nginx_configuration_preserves_previous_release(self):
        def fail(*args, **kwargs):
            self.calls.append(args)
            if args == ('nginx', '-t'):
                raise RuntimeError('invalid nginx')
            return ''
        self.run.side_effect = fail
        with self.assertRaises(RuntimeError):
            deploy.deploy(IMAGE)
        self.assert_preserved()

    def test_tls_failure_after_switch_restores_proxy(self):
        self.request.side_effect = [{}, RuntimeError('TLS failure')]
        with self.assertRaises(RuntimeError):
            deploy.deploy(IMAGE)
        self.assert_preserved()
        self.assertEqual(2, self.calls.count(('systemctl', 'reload-or-restart', 'nginx')))

    def test_success_records_new_release_before_draining_old(self):
        deploy.deploy(IMAGE)
        current = json.loads((self.root / 'state.json').read_text())
        self.assertEqual(28090, current['port'])
        self.assertEqual(IMAGE, current['previous'])
        self.assertIn('127.0.0.1:28090', deploy.NGINX_CONFIG.read_text())
        self.assertIn(('docker', 'stop', 'rougether-ai-18090'), self.calls)

    def test_smoke_rejects_bad_provider_data_without_assert(self):
        self.smoke.side_effect = None
        # patch를 우회해 원래 함수의 검증 경로를 직접 실행함.
        module_spec = importlib.util.spec_from_file_location('original_deploy', spec.origin)
        module = importlib.util.module_from_spec(module_spec)
        module_spec.loader.exec_module(module)
        with patch.object(module, 'request', return_value={'model': 'embed', 'vectors': [[float('nan'), 0]]}):
            with self.assertRaises(RuntimeError):
                module.smoke('http://localhost', self.env)


if __name__ == '__main__':
    unittest.main()
