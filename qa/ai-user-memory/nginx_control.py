"""실제 Nginx에서 배치 포트 공존, 내부 인증 경로와 유효 Host 전달을 검사함."""
import json
from pathlib import Path
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]


def run(*args, check=True):
    return subprocess.run(args, check=check, capture_output=True, text=True).stdout.strip()


def main():
    prefix = 'rougether-ai-route-' + uuid.uuid4().hex[:12]
    api, proxy = prefix + '-api', prefix + '-proxy'
    network = prefix + '-net'
    fixture = '''import http.server,threading
class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_GET(self):
        self.send_response(200); self.end_headers(); self.wfile.write(b'{"status":"ok"}')
    def do_POST(self):
        # Tomcat과 같이 HTTP Host의 밑줄을 거부해 upstream 이름 유출을 검출함.
        self.send_response(400 if '_' in self.headers.get('Host','') else 401)
        self.end_headers()
for port in (18080,8082):
    server=http.server.ThreadingHTTPServer(('0.0.0.0',port),Handler)
    threading.Thread(target=server.serve_forever,daemon=True).start()
threading.Event().wait()
'''
    probe = '''import json,urllib.request,urllib.error
results={}
for name,port,path,method in [
 ('public_health',8080,'/api/v1/health','GET'),
 ('public_control',8080,'/internal/ai-users/seed','POST'),
 ('private_control',38082,'/internal/ai-users/seed','POST'),
 ('batch_health',8082,'/actuator/health','GET')]:
    request=urllib.request.Request('http://127.0.0.1:'+str(port)+path,
        None if method=='GET' else b'{}',{'Content-Type':'application/json'},method=method)
    try:
        with urllib.request.urlopen(request,timeout=3) as response: results[name]=response.status
    except urllib.error.HTTPError as error: results[name]=error.code
print(json.dumps(results))
'''
    try:
        run('docker', 'pull', 'python:3.13-slim')
        run('docker', 'pull', 'nginx:alpine')
        run('docker', 'network', 'create', '--internal', network)
        run('docker', 'run', '-d', '--name', api, '--network', network,
            '--memory', '128m', '--pids-limit', '64', '--read-only',
            'python:3.13-slim', 'python', '-c', fixture)
        with tempfile.TemporaryDirectory(prefix=prefix) as folder:
            functions = Path(folder) / 'functions.sh'
            config = Path(folder) / 'default.conf'
            source = (ROOT / '.github/scripts/deploy-ec2-with-rollback.sh').read_text()
            functions.write_text(source.split('# BEGIN_DEPLOY_EXECUTION', 1)[0])
            run('bash', '-c', 'source "$1"; ROUGETHER_PRIVATE_IP=10.0.1.10; render_nginx_config "$2" 18080 ""',
                'render', str(functions), str(config))
            run('docker', 'run', '-d', '--name', proxy, '--network', 'container:' + api,
                '--memory', '128m', '--pids-limit', '64',
                '-v', str(config) + ':/etc/nginx/conf.d/default.conf:ro', 'nginx:alpine')
            for attempt in range(30):
                try:
                    result = json.loads(run('docker', 'exec', api, 'python', '-c', probe))
                    break
                except (subprocess.CalledProcessError, ValueError):
                    if attempt == 29:
                        raise
                    time.sleep(1)
            expected = {'public_health': 200, 'public_control': 404,
                        'private_control': 401, 'batch_health': 200}
            if result != expected:
                raise AssertionError({'expected': expected, 'actual': result})
            print(json.dumps({'nginx_control_contract': 'passed', **result}))
    finally:
        for name in (proxy, api):
            run('docker', 'rm', '-f', name, check=False)
        run('docker', 'network', 'rm', network, check=False)


if __name__ == '__main__':
    main()
