#!/usr/bin/env python3
"""검증된 같은 소스 트리의 PR 이미지에만 재사용을 허용함."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import tarfile

MODULES = ('user-api', 'admin-api', 'batch')
SCHEMA = 1


def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()


def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', path], text=True))


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def image_tag(module):
    return f'rougether-{module}:verified-ci'


def validate_receipt(receipt, tree, run_id, attempt, artifact_id):
    if not isinstance(receipt, dict) or not isinstance(receipt.get('images'), dict):
        raise ValueError('invalid receipt structure')
    expected = {'schema': SCHEMA, 'source_tree': tree, 'run_id': int(run_id),
                'run_attempt': int(attempt), 'image_artifact_id': int(artifact_id)}
    for key, value in expected.items():
        if receipt.get(key) != value:
            raise ValueError(f'receipt mismatch: {key}')
    if not re.fullmatch(r'[0-9a-f]{40}', receipt.get('source_commit', '')):
        raise ValueError('invalid source commit')
    if not re.fullmatch(r'[0-9a-f]{64}', receipt.get('archive_sha256', '')):
        raise ValueError('invalid archive checksum')
    if set(receipt.get('images', {})) != set(MODULES):
        raise ValueError('incomplete module set')
    for image_id in receipt['images'].values():
        if not re.fullmatch(r'sha256:[0-9a-f]{64}', image_id):
            raise ValueError('invalid image ID')


def eligible_run(run, repo, head):
    return (run.get('path') == '.github/workflows/pr-gate.yml'
            and run.get('event') == 'pull_request'
            and run.get('status') == 'completed' and run.get('conclusion') == 'success'
            and run.get('head_sha') == head
            and (run.get('repository') or {}).get('full_name', '').lower() == repo.lower()
            and (run.get('head_repository') or {}).get('full_name', '').lower() == repo.lower())


def find_artifact(artifacts, name):
    found = [a for a in artifacts if a.get('name') == name and not a.get('expired', True)]
    if len(found) != 1:
        raise ValueError(f'missing or ambiguous artifact: {name}')
    return found[0]


def resolve(repo, sha, tree):
    pulls = api(f'repos/{repo}/commits/{sha}/pulls')
    for pr in pulls:
        if (not pr.get('merged_at') or pr.get('merge_commit_sha') != sha
                or pr.get('base', {}).get('ref') != 'main'
                or (pr.get('head', {}).get('repo') or {}).get('full_name', '').lower() != repo.lower()):
            continue
        head = pr['head']['sha']
        runs = api(f'repos/{repo}/actions/workflows/pr-gate.yml/runs?event=pull_request&head_sha={head}&per_page=5')['workflow_runs']
        # 최신 실행이 실패/취소/진행 중이면 이전 성공으로 덮지 않음.
        if not runs or not eligible_run(runs[0], repo, head):
            continue
        run = runs[0]
        run_id, attempt = run['id'], run['run_attempt']
        artifacts = api(f'repos/{repo}/actions/runs/{run_id}/artifacts?per_page=100')['artifacts']
        bundle = find_artifact(artifacts, f'backend-images-{attempt}')
        receipt_artifact = find_artifact(artifacts, f'backend-receipt-{attempt}')
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run(['gh', 'run', 'download', str(run_id), '--repo', repo,
                            '--name', receipt_artifact['name'], '--dir', directory], check=True)
            files = list(Path(directory).iterdir())
            if len(files) != 1 or files[0].name != 'manifest.json' or files[0].is_symlink():
                raise ValueError('invalid receipt artifact contents')
            receipt = json.loads(files[0].read_text())
        validate_receipt(receipt, tree, run_id, attempt, bundle['id'])
        source = api(f'repos/{repo}/git/commits/{receipt["source_commit"]}')
        if source['tree']['sha'] != tree:
            raise ValueError('source commit tree differs from main')
        if receipt['source_commit'] != head and head not in [p['sha'] for p in source['parents']]:
            raise ValueError('source commit is unrelated to PR head')
        return {'reused': 'true', 'run_id': run_id, 'attempt': attempt,
                'artifact_id': bundle['id'], 'receipt_id': receipt_artifact['id']}
    return {'reused': 'false'}


def create_receipt(args):
    images = {m: subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Id}}',
                                         image_tag(m)], text=True).strip() for m in MODULES}
    receipt = {'schema': SCHEMA, 'source_commit': git('rev-parse', 'HEAD'),
               'source_tree': git('rev-parse', 'HEAD^{tree}'),
               'run_id': int(os.environ['GITHUB_RUN_ID']),
               'run_attempt': int(os.environ['GITHUB_RUN_ATTEMPT']),
               'image_artifact_id': int(args.artifact_id),
               'archive_sha256': digest(args.archive), 'images': images}
    Path(args.receipt).write_text(json.dumps(receipt, indent=2) + '\n')



def archive_identities(path):
    # classic store는 config digest, containerd store는 manifest digest를 ID로 반환함.
    # 같은 검증된 config를 가리키는 아카이브 내부 해시만 허용함.
    with tarfile.open(path) as archive:
        manifest = json.load(archive.extractfile('manifest.json'))
        identities = {}
        by_config = {}
        for module in MODULES:
            matches = [item for item in manifest if image_tag(module) in item.get('RepoTags', [])]
            if len(matches) != 1:
                raise ValueError(f'archive module mismatch: {module}')
            config = archive.extractfile(matches[0]['Config']).read()
            config_id = 'sha256:' + hashlib.sha256(config).hexdigest()
            identities[module] = {config_id}
            by_config[config_id] = module
        if 'index.json' in archive.getnames():
            index = json.load(archive.extractfile('index.json'))
            for descriptor in index.get('manifests', []):
                image_id = descriptor.get('digest', '')
                if not re.fullmatch(r'sha256:[0-9a-f]{64}', image_id):
                    raise ValueError('invalid archive manifest digest')
                raw = archive.extractfile('blobs/sha256/' + image_id.split(':')[1]).read()
                if 'sha256:' + hashlib.sha256(raw).hexdigest() != image_id:
                    raise ValueError('archive manifest digest mismatch')
                image = json.loads(raw)
                module = by_config.get(image.get('config', {}).get('digest'))
                if module:
                    identities[module].add(image_id)
        return identities


def load_bundle(args):
    receipt = json.loads(Path(args.receipt).read_text())
    validate_receipt(receipt, git('rev-parse', 'HEAD^{tree}'), args.run_id, args.attempt, args.artifact_id)
    if digest(args.archive) != receipt['archive_sha256']:
        raise ValueError('image archive checksum mismatch')
    identities = archive_identities(args.archive)
    for module in MODULES:
        if receipt['images'][module] not in identities[module]:
            raise ValueError(f'archive image identity mismatch: {module}')
    subprocess.run(['docker', 'image', 'load', '--input', args.archive], check=True)
    for module in MODULES:
        details = json.loads(subprocess.check_output(['docker', 'image', 'inspect', image_tag(module)], text=True))[0]
        labels = details['Config'].get('Labels') or {}
        if (details['Id'] not in identities[module]
                or labels.get('com.rougether.source-tree') != receipt['source_tree']
                or labels.get('com.rougether.module') != module
                or details.get('Os') != 'linux' or details.get('Architecture') != 'amd64'):
            raise ValueError(f'image identity mismatch: {module}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['resolve', 'receipt', 'load'])
    parser.add_argument('--archive', default='bundle/images.tar')
    parser.add_argument('--receipt', default='bundle/manifest.json')
    parser.add_argument('--artifact-id')
    parser.add_argument('--run-id')
    parser.add_argument('--attempt')
    args = parser.parse_args()
    if args.command == 'resolve':
        result = {'reused': 'false'}
        if os.environ.get('FORCE_VERIFICATION') != 'true':
            try:
                result = resolve(os.environ['GITHUB_REPOSITORY'], git('rev-parse', 'HEAD'), git('rev-parse', 'HEAD^{tree}'))
            except (subprocess.CalledProcessError, ValueError, KeyError, OSError) as error:
                print(f'::warning::PR artifact reuse unavailable ({type(error).__name__}); running full verification')
        result['source_tree'] = git('rev-parse', 'HEAD^{tree}')
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            for key, value in result.items():
                output.write(f'{key}={value}\n')
        print(json.dumps(result))
    elif args.command == 'receipt':
        create_receipt(args)
    else:
        load_bundle(args)


if __name__ == '__main__':
    main()
