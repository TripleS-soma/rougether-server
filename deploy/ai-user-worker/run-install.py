#!/usr/bin/env python3
"""검토한 워커 파일만 SSM으로 전송해 API 호스트에 설치함."""
import base64
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import time

ROOT = Path(__file__).resolve().parents[2]
FILES = [
    'ai-user-worker/Dockerfile', 'ai-user-worker/Dockerfile.dockerignore',
    'ai-user-worker/worker.py', 'ai-service/rougether_ai/ai_user_policy.py',
    'deploy/ai-service/configure-client.py', 'deploy/ai-user-worker/install.py',
    'deploy/ai-user-worker/rougether-ai-users.service', 'deploy/ai-user-worker/rougether-ai-users.timer',
]


def aws(*args):
    return json.loads(subprocess.check_output(['aws', '--region', 'ap-northeast-2', *args, '--output', 'json']))


def install(instance, enable=False):
    if not re.fullmatch(r'i-[a-f0-9]+', instance):
        raise ValueError('instance ID required')
    bundle = io.BytesIO()
    with tarfile.open(fileobj=bundle, mode='w:gz') as archive:
        for name in FILES:
            archive.add(ROOT / name, arcname=name, recursive=False)
    encoded = base64.b64encode(bundle.getvalue()).decode()
    digest = hashlib.sha256(bundle.getvalue()).hexdigest()
    script = f'''set -eu
python3 - <<'PY'
import base64, io, pathlib, tarfile
root = pathlib.Path('/opt/rougether-server')
root.mkdir(mode=0o700, parents=True, exist_ok=True)
with tarfile.open(fileobj=io.BytesIO(base64.b64decode('{encoded}')), mode='r:gz') as archive:
    for member in archive.getmembers():
        path = pathlib.PurePosixPath(member.name)
        if not member.isfile() or path.is_absolute() or '..' in path.parts:
            raise ValueError('invalid worker bundle member')
        target = root / member.name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(archive.extractfile(member).read())
        target.chmod(0o644)
PY
python3 /opt/rougether-server/deploy/ai-service/configure-client.py user-api
python3 /opt/rougether-server/deploy/ai-user-worker/install.py{' --enable' if enable else ''}
'''
    sent = aws('ssm', 'send-command', '--instance-ids', instance, '--document-name', 'AWS-RunShellScript',
        '--parameters', json.dumps({'commands': [script], 'executionTimeout': ['900']}))
    command = sent['Command']['CommandId']
    print(json.dumps({'command': command, 'bundle_sha256': digest}), flush=True)
    for _ in range(95):
        time.sleep(10)
        try:
            state = aws('ssm', 'get-command-invocation', '--command-id', command, '--instance-id', instance)
        except subprocess.CalledProcessError:
            continue
        if state['Status'] in ('Pending', 'InProgress', 'Delayed'):
            continue
        print(state.get('StandardOutputContent', ''))
        print(state.get('StandardErrorContent', ''), file=sys.stderr)
        if state['Status'] != 'Success':
            raise RuntimeError('worker installation unsuccessful')
        return
    raise RuntimeError('installation wait timed out; inspect command before retry')


if __name__ == '__main__':
    if len(sys.argv) not in (2, 3) or (len(sys.argv) == 3 and sys.argv[2] != '--enable'):
        raise SystemExit('instance ID and optional --enable required')
    install(sys.argv[1], enable=len(sys.argv) == 3)
