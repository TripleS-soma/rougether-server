#!/usr/bin/env python3
"""API 호스트에서 기존 SSM 연결 설정으로 단일 예약 워커를 설치함."""
import base64
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]


def run(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout.strip()


def write(path, value, mode=0o600):
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(value)
    temporary.chmod(mode)
    temporary.replace(path)


def install(enable=False):
    config = json.loads(json.loads(run('aws', 'ssm', 'get-parameter', '--region', 'ap-northeast-2',
        '--name', '/rougether-dev/ai/client', '--with-decryption'))['Parameter']['Value'])
    if config.get('AI_USERS_ENABLED') != 'true':
        raise ValueError('AI user client configuration disabled')
    token = config.get('AI_USERS_CONTROL_TOKEN', '')
    if len(token) < 32 or not all(33 <= ord(c) <= 126 for c in token):
        raise ValueError('invalid control token')
    folder = ROOT / 'ai-user-worker'
    folder.mkdir(mode=0o700, parents=True, exist_ok=True)
    ca = base64.b64decode(config['AI_SERVICE_CA_CERTIFICATE_BASE64'], validate=True).decode()
    write(folder / 'ai-ca.crt', ca, 0o644)
    values = {
        'AI_USER_CONTROL_URL': 'http://127.0.0.1:38082', 'AI_USER_CONTROL_TOKEN': token,
        'AI_USER_SERVICE_URL': config['AI_SERVICE_BASE_URL'],
        'AI_USER_SERVICE_TOKEN': config['AI_SERVICE_TOKEN'],
        'AI_USER_MAX_JOBS': '10', 'AI_USER_MAX_SECONDS': '600', 'AI_USER_MEMORY_BATCH_LIMIT': '10',
        'AI_USER_ALLOW_PRIVATE_HTTP': 'false',
    }
    if any('\n' in value or '\r' in value for value in values.values()):
        raise ValueError('invalid worker configuration')
    write(folder / '.env', ''.join(key + '=' + value + '\n' for key, value in values.items()))
    run('docker', 'build', '-t', 'rougether-ai-users:local', '-f', str(folder / 'Dockerfile'), str(ROOT))
    for suffix in ('service', 'timer'):
        filename = 'rougether-ai-users.' + suffix
        write(Path('/etc/systemd/system') / filename,
              (ROOT / 'deploy/ai-user-worker' / filename).read_text(), 0o644)
    run('systemd-analyze', 'verify', '/etc/systemd/system/rougether-ai-users.service',
        '/etc/systemd/system/rougether-ai-users.timer')
    run('systemctl', 'daemon-reload')
    if enable:
        run('systemctl', 'enable', '--now', 'rougether-ai-users.timer')
    print(json.dumps({'worker_installed': True, 'timer_enabled': enable,
                      'schedule': '08:00-23:30 Asia/Seoul, every 30 minutes'}))


if __name__ == '__main__':
    try:
        if sys.argv[1:] not in ([], ['--enable']):
            raise ValueError('expected optional --enable')
        install(enable=sys.argv[1:] == ['--enable'])
    except Exception as error:
        print('Worker installation failed: ' + type(error).__name__, file=sys.stderr)
        sys.exit(1)
