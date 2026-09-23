#!/usr/bin/env python3
"""SSM으로 AI 독립 배포를 실행한다. AWS_PROFILE 또는 OIDC 자격증명을 사용한다."""
import base64
import json
from pathlib import Path
import re
import subprocess
import sys
import time


def aws(*args):
    return json.loads(subprocess.check_output(['aws', *args, '--output', 'json']))


instance, image = sys.argv[1:]
if not re.fullmatch(r'i-[a-f0-9]+', instance) or not re.fullmatch(r'\d{12}\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com/[a-z0-9/-]+@sha256:[a-f0-9]{64}', image):
    raise SystemExit('instance ID and immutable ECR digest required')
script = base64.b64encode(Path(__file__).with_name('deploy.py').read_bytes()).decode()
commands = [f"set -eu\ninstall -d -m 755 /opt/rougether-ai\nprintf '%s' '{script}' | base64 -d > /opt/rougether-ai/deploy.py\nchmod 700 /opt/rougether-ai/deploy.py\npython3 /opt/rougether-ai/deploy.py '{image}'"]
result = aws('ssm', 'send-command', '--instance-ids', instance, '--document-name', 'AWS-RunShellScript',
             '--parameters', json.dumps({'commands': commands, 'executionTimeout': ['900']}))
command = result['Command']['CommandId']
print('AI deployment command: ' + command, flush=True)
for _ in range(100):
    time.sleep(10)
    try:
        result = aws('ssm', 'get-command-invocation', '--command-id', command, '--instance-id', instance)
    except subprocess.CalledProcessError:
        continue
    if result['Status'] in ('Pending', 'InProgress', 'Delayed'):
        continue
    print(result.get('StandardOutputContent', ''))
    print(result.get('StandardErrorContent', ''), file=sys.stderr)
    raise SystemExit(0 if result['Status'] == 'Success' else 1)
raise SystemExit('SSM deployment wait timed out; inspect command before retry')
