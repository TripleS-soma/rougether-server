#!/usr/bin/env python3
"""API EC2에서 실행. SSM의 승인된 AI 연결 설정을 지정 앱 env에 반영하고 재기동은 하지 않는다."""
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.parse

app = sys.argv[1]
if app not in ('user-api', 'batch'):
    raise SystemExit('user-api or batch required')
result = subprocess.check_output(['aws', 'ssm', 'get-parameter', '--name', '/rougether-dev/ai/client',
    '--with-decryption', '--region', os.environ.get('AWS_REGION', 'ap-northeast-2')])
config = json.loads(json.loads(result)['Parameter']['Value'])
allowed = {'AI_SERVICE_ENABLED', 'AI_SERVICE_BASE_URL', 'AI_SERVICE_TOKEN', 'AI_SERVICE_TIMEOUT',
           'AI_SERVICE_ALLOW_INSECURE_HTTP', 'AI_SERVICE_CA_CERTIFICATE_BASE64'}
if set(config) != allowed or any(not isinstance(v, str) or '\n' in v or '\r' in v for v in config.values()):
    raise SystemExit('Invalid AI client configuration')
if config['AI_SERVICE_ENABLED'] not in ('true', 'false') or config['AI_SERVICE_ALLOW_INSECURE_HTTP'] != 'false':
    raise SystemExit('Invalid activation or TLS configuration')
url = urllib.parse.urlparse(config['AI_SERVICE_BASE_URL'])
if url.scheme != 'https' or not url.hostname or url.username or url.query or url.fragment or url.path:
    raise SystemExit('HTTPS origin required')
path = Path('/etc/rougether') / (app + '.env')
original = path.read_text()
backup = path.with_suffix('.env.before-ai')
if not backup.exists():
    backup.write_text(original)
    backup.chmod(0o600)
lines = [line for line in original.splitlines() if line.split('=', 1)[0] not in allowed]
lines.extend(key + '=' + value for key, value in config.items())
temporary = path.with_suffix('.env.ai-tmp')
temporary.write_text('\n'.join(lines) + '\n')
temporary.chmod(0o600)
temporary.replace(path)
print(app + ' AI configuration updated; restart required')
