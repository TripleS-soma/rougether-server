#!/usr/bin/env python3
"""AI EC2에서 root로 실행하는 독립 배포. 비밀은 SSM에서만 읽고 출력하지 않는다."""
import fcntl
import json
import math
import os
from pathlib import Path
import re
import ssl
import subprocess
import sys
import time
import urllib.request

ROOT = Path('/etc/rougether-ai')
REGION = os.environ.get('AWS_REGION', 'ap-northeast-2')
PREFIX = os.environ.get('AI_PARAMETER_PREFIX', '/rougether-dev')
NGINX_CONFIG = Path('/etc/nginx/conf.d/rougether-ai.conf')


def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, **kwargs).stdout.strip()


def parameter(name):
    return json.loads(run('aws', 'ssm', 'get-parameter', '--region', REGION, '--name', name, '--with-decryption'))['Parameter']['Value']


def write(path, value):
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(value)
    temporary.chmod(0o600)
    temporary.replace(path)


def request(base, path, token=None, body=None, context=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(base + path, None if body is None else json.dumps(body).encode(), headers)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context))
    with opener.open(req, timeout=100) as response:
        data = response.read(16777217)
        if len(data) > 16777216:
            raise ValueError('response too large')
        return json.loads(data)


def smoke(base, env):
    dim = int(env['AI_EMBEDDING_DIMENSIONS'])
    result = request(base, '/internal/v1/embeddings', env['AI_SERVICE_TOKEN'],
        {'model': env['AI_EMBEDDING_MODEL'], 'dimensions': dim, 'inputs': ['Deployment connection check']})
    vectors = result.get('vectors', [])
    if result.get('model') != env['AI_EMBEDDING_MODEL'] or len(vectors) != 1:
        raise RuntimeError('embedding model or count mismatch')
    if len(vectors[0]) != dim or not all(type(x) in (int, float) and math.isfinite(x) for x in vectors[0]):
        raise RuntimeError('invalid embedding vector')
    result = request(base, '/internal/v1/completions', env['AI_SERVICE_TOKEN'],
        {'model': env['AI_CHAT_MODEL'], 'systemPrompt': 'Return a JSON object with status ok.',
         'userPrompt': 'Deployment check', 'maxTokens': 512, 'jsonMode': True, 'reasoningEffort': 'low'})
    if result.get('model') != env['AI_CHAT_MODEL'] or not isinstance(result.get('content'), str) or not result['content'].strip():
        raise RuntimeError('invalid completion')
    if env.get('AI_USERS_ENABLED') == 'true':
        inventory = request(base, '/internal/v1/ai-users/inventory', env['AI_SERVICE_TOKEN'],
                            {'aiUserId': 1, 'cursor': ''})
        if not isinstance(inventory.get('records'), list):
            raise RuntimeError('memory inventory unavailable')


def ensure_memory(env):
    # 기존 모델 전용 호스트에 메모리 기능을 실수로 올리지 않도록 용량을 확인함.
    total = int(next(line.split()[1] for line in Path('/proc/meminfo').read_text().splitlines()
                     if line.startswith('MemTotal:')))
    if total < 7 * 1024 * 1024:
        raise RuntimeError('AI memory deployment requires an 8 GiB host')
    if env.get('AI_USER_NEO4J_URI') != 'bolt://rougether-ai-neo4j:7687' or env.get('AI_USER_NEO4J_USER', 'neo4j') != 'neo4j':
        raise ValueError('managed Neo4j origin required')
    password = env.get('AI_USER_NEO4J_PASSWORD', '')
    if len(password) < 32 or not env.get('ANTHROPIC_API_KEY'):
        raise ValueError('missing memory credentials')
    network = 'rougether-ai-memory'
    for kind, name in [('network', network), ('volume', 'rougether-ai-neo4j-data')]:
        inspected = subprocess.run(['docker', kind, 'inspect', name], capture_output=True)
        if inspected.returncode:
            run('docker', kind, 'create', name)
    name = 'rougether-ai-neo4j'
    inspected = subprocess.run(['docker', 'container', 'inspect', name], capture_output=True, text=True)
    if inspected.returncode:
        folder = ROOT / 'memory'
        folder.mkdir(mode=0o700, exist_ok=True)
        write(folder / 'neo4j.env', '\n'.join([
            'NEO4J_AUTH=neo4j/' + password, 'NEO4J_dbms_usage__report_enabled=false',
            'NEO4J_server_memory_heap_initial__size=512m', 'NEO4J_server_memory_heap_max__size=1G',
            'NEO4J_server_memory_pagecache_size=1G', '']))
        run('docker', 'pull', 'neo4j:5.26.32-community')
        run('docker', 'run', '-d', '--name', name, '--restart', 'unless-stopped', '--network', network,
            '--memory', '3g', '--memory-swap', '3g', '--cpus', '1', '--pids-limit', '256',
            '--env-file', str(folder / 'neo4j.env'), '-v', 'rougether-ai-neo4j-data:/data',
            '--log-opt', 'max-size=10m', '--log-opt', 'max-file=3', 'neo4j:5.26.32-community')
    elif not json.loads(inspected.stdout)[0]['State']['Running']:
        run('docker', 'start', name)
    for _ in range(90):
        # 비밀번호를 명령 인수나 로그에 싣지 않고 컨테이너의 기존 env를 사용함.
        check = subprocess.run(['docker', 'exec', name, 'sh', '-c',
            'cypher-shell -u neo4j -p "${NEO4J_AUTH#*/}" "RETURN 1" >/dev/null 2>&1'], capture_output=True)
        if check.returncode == 0:
            return network
        time.sleep(1)
    raise RuntimeError('Neo4j not ready')


def deploy(image):
    if not re.fullmatch(r'\d{12}\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com/[a-z0-9/-]+@sha256:[a-f0-9]{64}', image):
        raise ValueError('immutable ECR digest required')
    ROOT.mkdir(mode=0o700, exist_ok=True)
    with (ROOT / 'deploy.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return deploy_locked(image)


def deploy_locked(image):
    state_path = ROOT / 'state.json'
    previous = json.loads(state_path.read_text()) if state_path.exists() else None
    port = 28090 if previous and previous['port'] == 18090 else 18090
    name = f'rougether-ai-{port}'
    print('Loading AI runtime configuration', flush=True)
    runtime = json.loads(parameter(PREFIX + '/ai/runtime'))
    env = dict(runtime['env'])
    if not re.fullmatch(r'10\.\d{1,3}\.\d{1,3}\.\d{1,3}', runtime['private_ip']):
        raise ValueError('invalid private IP')
    env['AI_PROVIDER_API_KEY'] = parameter(PREFIX + '/llm/api-key')
    allowed = {'AI_SERVICE_TOKEN', 'AI_PROVIDER_API_KEY', 'AI_PROVIDER_BASE_URL', 'AI_CHAT_MODEL',
               'AI_EMBEDDING_MODEL', 'AI_EMBEDDING_DIMENSIONS', 'AI_CHAT_CONCURRENCY', 'AI_EMBEDDING_CONCURRENCY',
               'AI_PROVIDER_TIMEOUT_SECONDS', 'AI_REQUEST_TIMEOUT_SECONDS', 'AI_MAX_RETRIES',
               'AI_USERS_ENABLED', 'AI_USER_NEO4J_URI', 'AI_USER_NEO4J_USER', 'AI_USER_NEO4J_DATABASE',
               'AI_USER_NEO4J_PASSWORD', 'ANTHROPIC_API_KEY', 'AI_USER_MODEL', 'AI_USER_HUMAN_MODEL', 'AI_USER_MEMORY_MODEL'}
    if set(env) - allowed or any(not isinstance(v, str) or '\n' in v or '\r' in v for v in env.values()):
        raise ValueError('invalid runtime configuration')
    if env.get('AI_USERS_ENABLED', 'false') not in ('true', 'false'):
        raise ValueError('invalid AI user activation')
    memory_enabled = env.get('AI_USERS_ENABLED') == 'true'
    network = ensure_memory(env) if memory_enabled else None
    folder = ROOT / str(port)
    folder.mkdir(mode=0o700, exist_ok=True)
    for key, filename in [('certificate', 'server.crt'), ('private_key', 'server.key'), ('ca_certificate', 'ca.crt')]:
        write(folder / filename, runtime[key])
    write(folder / 'service.env', ''.join(f'{key}={value}\n' for key, value in env.items()))
    registry = image.split('/')[0]
    password = run('aws', 'ecr', 'get-login-password', '--region', REGION)
    run('docker', 'login', '--username', 'AWS', '--password-stdin', registry, input=password)
    print('Pulling AI image', flush=True)
    run('docker', 'pull', image)
    subprocess.run(['docker', 'rm', '-f', name], capture_output=True)
    switched = False
    nginx_path = NGINX_CONFIG
    old_config = nginx_path.read_text() if nginx_path.exists() else None
    try:
        network_args = ['--network', network] if network else []
        memory_limit = '2g' if memory_enabled else '512m'
        run('docker', 'run', '-d', '--name', name, '--restart', 'unless-stopped', '--memory', memory_limit,
            '--memory-swap', memory_limit, '--cpus', '1', '--pids-limit', '128', '--read-only', *network_args,
            '--tmpfs', '/tmp:size=16m,noexec,nosuid', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
            '--stop-timeout', '195', '--env-file', str(folder / 'service.env'), '-p', f'127.0.0.1:{port}:8090',
            '--log-opt', 'max-size=10m', '--log-opt', 'max-file=3', image)
        for attempt in range(60):
            try:
                request(f'http://127.0.0.1:{port}', '/health/ready')
                break
            except OSError:
                time.sleep(1)
        else:
            raise RuntimeError('candidate not ready')
        print('Checking candidate with model provider', flush=True)
        smoke(f'http://127.0.0.1:{port}', env)
        config = f'''server {{
    listen {runtime['private_ip']}:8443 ssl;
    server_name _;
    ssl_certificate {folder}/server.crt;
    ssl_certificate_key {folder}/server.key;
    ssl_protocols TLSv1.2 TLSv1.3;
    client_max_body_size 512k;
    access_log off;
    location /internal/v1/ai-users/ {{
        proxy_pass http://127.0.0.1:{port};
        proxy_connect_timeout 3s;
        proxy_read_timeout 190s;
        proxy_send_timeout 10s;
        proxy_next_upstream off;
    }}
    location / {{
        proxy_pass http://127.0.0.1:{port};
        proxy_connect_timeout 3s;
        proxy_read_timeout 95s;
        proxy_send_timeout 10s;
        proxy_next_upstream off;
    }}
}}
'''
        write(nginx_path, config)
        run('nginx', '-t')
        switched = True
        run('systemctl', 'enable', 'nginx')
        run('systemctl', 'reload-or-restart', 'nginx')
        context = ssl.create_default_context(cadata=runtime['ca_certificate'])
        request('https://' + runtime['private_ip'] + ':8443', '/health/ready', context=context)
        write(state_path, json.dumps({'image': image, 'port': port, 'name': name, 'previous': previous and previous['image']}) + '\n')
    except BaseException:
        if old_config is not None:
            write(nginx_path, old_config)
        else:
            nginx_path.unlink(missing_ok=True)
        if switched:
            run('systemctl', 'reload-or-restart', 'nginx')
        subprocess.run(['docker', 'rm', '-f', name], capture_output=True)
        raise
    if previous:
        # 이미 수락한 요청은 기존 worker에서 끝내도록 최대 deadline 이상 대기함.
        time.sleep(190)
        try:
            run('docker', 'stop', previous['name'])
            run('docker', 'rm', previous['name'])
        except subprocess.CalledProcessError:
            print('WARNING: AI release is active; previous container cleanup requires inspection', flush=True)
    print(json.dumps({'status': 'ready', 'image': image, 'port': port, 'provider_smoke': 'passed'}))


if __name__ == '__main__':
    try:
        deploy(sys.argv[1])
    except Exception as error:
        # HTTP 응답·SSM 값·subprocess 출력에는 자격증명이 포함될 수 있어 예외 타입만 출력함.
        print('AI deployment failed: ' + type(error).__name__, file=sys.stderr)
        sys.exit(1)
