"""JVM → FastAPI → 로컬 모의 공급자 계약 검증. 실제 공급자·DB·AWS 자격 증명을 사용하지 않음."""
import base64
import ssl
import tempfile
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[2]
requests = []


class Provider(BaseHTTPRequestHandler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        requests.append((self.path, body))
        if self.headers.get("Authorization") != "Bearer contract-provider-key":
            self.send_error(401)
            return
        if self.path == "/v1/embeddings":
            response = {"data": [{"index": 1, "embedding": [0, 1]}, {"index": 0, "embedding": [1, 0]}]}
        elif self.path == "/v1/chat/completions":
            response = {"choices": [{"message": {"content": '{"summary":"Contract verified"}'}}]}
        else:
            self.send_error(404)
            return
        payload = json.dumps(response).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


provider = ThreadingHTTPServer(("127.0.0.1", 0), Provider)
threading.Thread(target=provider.serve_forever, daemon=True).start()
with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
# 실제 환경에 남은 AI 설정이 검증 대상에 영향을 주지 않게 제거함.
env = {k: v for k, v in os.environ.items() if not k.startswith(("AI_", "LLM_"))}
env.update(AI_SERVICE_TOKEN="contract-test-token-12345678901234567890",
           AI_PROVIDER_API_KEY="contract-provider-key",
           AI_PROVIDER_BASE_URL=f"http://127.0.0.1:{provider.server_port}/v1",
           AI_CHAT_MODEL="contract-chat", AI_EMBEDDING_MODEL="contract-embedding", AI_EMBEDDING_DIMENSIONS="2")
cert_directory = tempfile.TemporaryDirectory(prefix="ai-contract-tls-")
cert = Path(cert_directory.name) / "server.crt"
key = Path(cert_directory.name) / "server.key"
subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
    "-subj", "/CN=AI contract", "-addext", "subjectAltName=IP:127.0.0.1", "-keyout", str(key), "-out", str(cert)],
    check=True, capture_output=True)
context = ssl.create_default_context(cafile=str(cert))
env["AI_SERVICE_CA_CERTIFICATE_BASE64"] = base64.b64encode(cert.read_bytes()).decode()
process = subprocess.Popen([str(ROOT / "ai-service/.venv/bin/python"), "-m", "uvicorn",
    "rougether_ai.app:create_app", "--factory", "--host", "127.0.0.1", "--port", str(port), "--no-access-log", "--ssl-certfile", str(cert), "--ssl-keyfile", str(key)],
    cwd=ROOT / "ai-service", env=env)
try:
    for _ in range(100):
        if process.poll() is not None:
            raise RuntimeError("AI 서비스 기동 실패")
        try:
            with urllib.request.urlopen(f"https://127.0.0.1:{port}/health/ready", timeout=1, context=context):
                break
        except OSError:
            time.sleep(0.1)
    else:
        raise RuntimeError("AI 서비스 기동 제한 시간 초과")
    env["AI_SERVICE_CONTRACT_URL"] = f"https://127.0.0.1:{port}"
    subprocess.run(["./gradlew", ":infra:llm:test", "--tests", "*AiServiceContractTest", "--rerun-tasks", "--console=plain"],
                   cwd=ROOT, env=env, check=True)
    if [path for path, _ in requests] != ["/v1/embeddings", "/v1/chat/completions"]:
        raise RuntimeError("호출 횟수 또는 경로 불일치")
    print("PASS JVM → FastAPI → local provider: embedding order, completion content, one call each")
    env["AI_SERVICE_BASE_URL"] = env["AI_SERVICE_CONTRACT_URL"]
    subprocess.run([str(ROOT / "ai-service/.venv/bin/python"), str(ROOT / "qa/ai-service-contract/smoke.py")],
                   cwd=ROOT, env=env, check=True)
    if [path for path, _ in requests] != ["/v1/embeddings", "/v1/chat/completions"] * 2:
        raise RuntimeError("운영 smoke 스크립트 호출 횟수 또는 경로 불일치")
finally:
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)
    provider.shutdown()
    provider.server_close()
    cert_directory.cleanup()
