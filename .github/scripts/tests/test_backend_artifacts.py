import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'backend_artifacts.py'
spec = importlib.util.spec_from_file_location('backend_artifacts', SCRIPT)
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)

REPO = 'TripleS-soma/rougether-server'
SHA, TREE, HEAD, SOURCE = ('a' * 40, 'b' * 40, 'c' * 40, 'd' * 40)


def receipt():
    return {'schema': 1, 'source_commit': SOURCE, 'source_tree': TREE, 'run_id': 123,
            'run_attempt': 2, 'image_artifact_id': 456, 'archive_sha256': 'e' * 64,
            'images': {m: 'sha256:' + 'f' * 64 for m in ci.MODULES}}


def run():
    return {'id': 123, 'run_attempt': 2, 'path': '.github/workflows/pr-gate.yml',
            'event': 'pull_request', 'status': 'completed', 'conclusion': 'success',
            'head_sha': HEAD, 'repository': {'full_name': REPO},
            'head_repository': {'full_name': REPO}}


class ReceiptTest(unittest.TestCase):
    def test_accepts_identical_tree_with_different_merge_commit(self):
        ci.validate_receipt(receipt(), TREE, 123, 2, 456)

    def test_rejects_wrong_tree_run_attempt_or_artifact(self):
        for key, wrong in [('source_tree', '0' * 40), ('run_id', 124),
                           ('run_attempt', 1), ('image_artifact_id', 999)]:
            data = receipt(); data[key] = wrong
            with self.subTest(key=key), self.assertRaises(ValueError):
                ci.validate_receipt(data, TREE, 123, 2, 456)

    def test_rejects_incomplete_modules_and_invalid_digests(self):
        for mutate in [lambda d: d['images'].pop('batch'),
                       lambda d: d.update(archive_sha256='not-a-digest'),
                       lambda d: d['images'].update(batch='not-an-image-id')]:
            data = receipt(); mutate(data)
            with self.assertRaises(ValueError):
                ci.validate_receipt(data, TREE, 123, 2, 456)

    def test_rejects_failed_cancelled_fork_and_different_workflow(self):
        for changes in [{'conclusion': 'failure'}, {'conclusion': 'cancelled'},
                        {'status': 'in_progress'}, {'head_sha': SHA},
                        {'head_repository': {'full_name': 'other/fork'}},
                        {'path': '.github/workflows/other.yml'}, {'event': 'workflow_dispatch'}]:
            data = run(); data.update(changes)
            with self.subTest(changes=changes):
                self.assertFalse(ci.eligible_run(data, REPO, HEAD))

    def test_expired_missing_and_duplicate_artifact_rejected(self):
        for artifacts in [[], [{'name': 'bundle', 'expired': True}],
                          [{'name': 'bundle', 'expired': False}] * 2]:
            with self.assertRaises(ValueError):
                ci.find_artifact(artifacts, 'bundle')

    def test_corrupt_bundle_is_rejected_before_docker_load(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / 'images.tar'; archive.write_bytes(b'corrupted')
            manifest = Path(directory) / 'manifest.json'; manifest.write_text(json.dumps(receipt()))
            from argparse import Namespace
            args = Namespace(receipt=manifest, archive=archive, run_id=123, attempt=2, artifact_id=456)
            with patch.object(ci, 'git', return_value=TREE), patch.object(ci.subprocess, 'run') as docker:
                with self.assertRaisesRegex(ValueError, 'checksum'):
                    ci.load_bundle(args)
                docker.assert_not_called()


    def test_valid_bundle_requires_all_image_ids_labels_and_architecture(self):
        from argparse import Namespace
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / 'images.tar'; archive.write_bytes(b'valid archive')
            data = receipt(); data['archive_sha256'] = ci.digest(archive)
            manifest = Path(directory) / 'manifest.json'; manifest.write_text(json.dumps(data))
            args = Namespace(receipt=manifest, archive=archive, run_id=123, attempt=2, artifact_id=456)
            def inspect(command, **kwargs):
                module = next(m for m in ci.MODULES if command[-1] == ci.image_tag(m))
                return json.dumps([{'Id': data['images'][module], 'Os': 'linux', 'Architecture': 'amd64',
                                    'Config': {'Labels': {'com.rougether.source-tree': TREE,
                                                          'com.rougether.module': module}}}])
            with patch.object(ci, 'git', return_value=TREE), patch.object(ci.subprocess, 'run') as load, \
                    patch.object(ci.subprocess, 'check_output', side_effect=inspect) as inspection:
                ci.load_bundle(args)
                self.assertEqual(inspection.call_count, 3)
                load.assert_called_once()
            broken = json.loads(inspect(['docker', ci.image_tag('user-api')]))
            broken[0]['Architecture'] = 'arm64'
            with patch.object(ci, 'git', return_value=TREE), patch.object(ci.subprocess, 'run'), \
                    patch.object(ci.subprocess, 'check_output', return_value=json.dumps(broken)):
                with self.assertRaisesRegex(ValueError, 'identity mismatch'):
                    ci.load_bundle(args)


class ResolutionTest(unittest.TestCase):
    def setUp(self):
        self.pr = {'merged_at': '2026-10-17T13:00:00Z', 'merge_commit_sha': SHA,
                   'base': {'ref': 'main'}, 'head': {'sha': HEAD, 'repo': {'full_name': REPO}}}
        self.execution = run()
        self.data = receipt()
        self.artifacts = [{'name': 'backend-images-2', 'id': 456, 'expired': False},
                          {'name': 'backend-receipt-2', 'id': 789, 'expired': False}]
        self.source_tree = TREE

    def fake_api(self, path):
        if path.endswith('/pulls'): return [self.pr]
        if '/workflows/' in path: return {'workflow_runs': [self.execution]}
        if path.endswith('/artifacts?per_page=100'): return {'artifacts': self.artifacts}
        if '/git/commits/' in path: return {'tree': {'sha': self.source_tree}, 'parents': [{'sha': HEAD}]}
        raise AssertionError(path)

    def download(self, args, **kwargs):
        directory = Path(args[args.index('--dir') + 1])
        (directory / 'manifest.json').write_text(json.dumps(self.data))

    def resolve(self):
        with patch.object(ci, 'api', side_effect=self.fake_api), \
                patch.object(ci.subprocess, 'run', side_effect=self.download):
            return ci.resolve(REPO, SHA, TREE)

    def test_reuses_only_bound_immutable_artifact(self):
        result = self.resolve()
        self.assertEqual(result, {'reused': 'true', 'run_id': 123, 'attempt': 2,
                                  'artifact_id': 456, 'receipt_id': 789})

    def test_different_merge_tree_requires_fresh_verification(self):
        self.data['source_tree'] = '0' * 40
        with self.assertRaisesRegex(ValueError, 'source_tree'):
            self.resolve()

    def test_dishonest_receipt_cannot_override_real_commit_tree(self):
        self.source_tree = '0' * 40
        with self.assertRaisesRegex(ValueError, 'tree differs'):
            self.resolve()

    def test_latest_failed_run_and_fork_do_not_reuse_old_success(self):
        self.execution['conclusion'] = 'failure'
        self.assertEqual(self.resolve(), {'reused': 'false'})
        self.execution = run(); self.pr['head']['repo']['full_name'] = 'other/fork'
        self.assertEqual(self.resolve(), {'reused': 'false'})

    def test_direct_push_without_matching_merge_runs_fresh(self):
        self.pr['merge_commit_sha'] = '0' * 40
        self.assertEqual(self.resolve(), {'reused': 'false'})

    def test_rerun_cannot_use_receipt_from_previous_attempt(self):
        self.execution['run_attempt'] = 3
        with self.assertRaises(ValueError): self.resolve()


if __name__ == '__main__': unittest.main()
