"""운영 전환 전 실제 공급자 연결 확인. AI_SERVICE_BASE_URL/TOKEN과 AI_* 모델 설정을 환경변수로 받음."""
import json
import math
import os
import urllib.error
import urllib.parse
import urllib.request

base = os.environ["AI_SERVICE_BASE_URL"].rstrip("/")
url = urllib.parse.urlparse(base)
if (url.scheme != "https" and not (url.scheme == "http" and url.hostname in {"localhost", "127.0.0.1", "::1"})) or url.username or url.password or url.query or url.fragment or url.path:
    raise SystemExit("AI_SERVICE_BASE_URL에 HTTPS origin 또는 localhost HTTP origin을 지정하세요")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())


def call(path, payload):
    request = urllib.request.Request(base + "/internal/v1/" + path, json.dumps(payload).encode(),
        {"Authorization": "Bearer " + os.environ["AI_SERVICE_TOKEN"], "Content-Type": "application/json"})
    try:
        with opener.open(request, timeout=100) as response:
            body = response.read(16777217)
            if len(body) > 16777216:
                raise SystemExit("FAIL: 응답 크기 초과")
            return json.loads(body)
    except urllib.error.HTTPError as error:
        raise SystemExit(f"FAIL {path}: HTTP {error.code}") from None
    except (OSError, ValueError):
        raise SystemExit(f"FAIL {path}: 연결 또는 응답 오류") from None


model = os.environ.get("AI_EMBEDDING_MODEL", "text-embedding-3-large")
dimensions = int(os.environ.get("AI_EMBEDDING_DIMENSIONS", "1024"))
embedded = call("embeddings", {"model": model, "dimensions": dimensions, "inputs": ["아침 산책", "Read a book"]})
vectors = embedded.get("vectors", [])
if embedded.get("model") != model or len(vectors) != 2 or any(
    not isinstance(vector, list) or len(vector) != dimensions
    or any(type(value) not in (float, int) or not math.isfinite(value) for value in vector)
    for vector in vectors
):
    raise SystemExit("FAIL embeddings: 모델·개수·차원·유한값 불일치")
model = os.environ.get("AI_CHAT_MODEL", "gpt-5.6-luna")
completed = call("completions", {"model": model, "systemPrompt": "Return a JSON object with status set to ok.",
    "userPrompt": "Connection check", "maxTokens": 512, "jsonMode": True, "reasoningEffort": "low"})
if completed.get("model") != model or not isinstance(completed.get("content"), str) or not completed["content"].strip():
    raise SystemExit("FAIL completions: 모델 또는 본문 오류")
print("PASS: 임베딩 2개 및 completion 응답 확인 (본문·토큰 출력 없음)")
