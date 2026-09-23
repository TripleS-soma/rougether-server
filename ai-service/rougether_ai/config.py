import os
from dataclasses import dataclass, field
from urllib.parse import urlparse


@dataclass(frozen=True)
class Settings:
    service_token: str = field(repr=False)
    provider_api_key: str = field(repr=False)
    provider_base_url: str = "https://api.openai.com/v1"
    chat_model: str = "gpt-5.6-luna"
    embedding_model: str = "text-embedding-3-large"
    embedding_dimensions: int = 1024
    provider_timeout_seconds: float = 30
    request_timeout_seconds: float = 90
    max_retries: int = 2
    retry_backoff_seconds: float = 1
    chat_concurrency: int = 2
    embedding_concurrency: int = 2
    max_request_bytes: int = 524288
    max_response_bytes: int = 16777216

    def __post_init__(self):
        if len(self.service_token) < 32 or any(not 33 <= ord(c) <= 126 for c in self.service_token):
            raise ValueError("AI_SERVICE_TOKEN은 공백 없는 ASCII 32자 이상이어야 함")
        if not self.provider_api_key.strip():
            raise ValueError("AI_PROVIDER_API_KEY가 필요함")
        url = urlparse(self.provider_base_url)
        if (url.scheme != "https" and not (url.scheme == "http" and url.hostname in {"localhost", "127.0.0.1", "::1"})) or not url.hostname or url.username or url.password or url.query or url.fragment:
            raise ValueError("공급자 URL은 HTTPS 또는 로컬 테스트 HTTP만 허용함")
        if not self.chat_model.strip() or not self.embedding_model.strip():
            raise ValueError("모델 설정이 필요함")
        if not 1 <= self.embedding_dimensions <= 3072:
            raise ValueError("임베딩 차원은 1~3072여야 함")
        if not 0 <= self.max_retries <= 3 or not 0 <= self.retry_backoff_seconds <= 10:
            raise ValueError("재시도 설정 범위 초과")
        if not 0 < self.provider_timeout_seconds <= self.request_timeout_seconds <= 90:
            raise ValueError("호출 제한 시간은 0~90초 범위여야 함")
        if not 1 <= self.chat_concurrency <= 16 or not 1 <= self.embedding_concurrency <= 16:
            raise ValueError("동시 실행 설정 범위 초과")
        if not 1 <= self.max_request_bytes <= 524288 or not 1 <= self.max_response_bytes <= 16777216:
            raise ValueError("본문 크기 제한 범위 초과")

    @classmethod
    def from_env(cls):
        return cls(
            service_token=os.environ.get("AI_SERVICE_TOKEN", ""),
            provider_api_key=os.environ.get("AI_PROVIDER_API_KEY", ""),
            provider_base_url=os.environ.get("AI_PROVIDER_BASE_URL", "https://api.openai.com/v1"),
            chat_model=os.environ.get("AI_CHAT_MODEL", "gpt-5.6-luna"),
            embedding_model=os.environ.get("AI_EMBEDDING_MODEL", "text-embedding-3-large"),
            embedding_dimensions=int(os.environ.get("AI_EMBEDDING_DIMENSIONS", "1024")),
            provider_timeout_seconds=float(os.environ.get("AI_PROVIDER_TIMEOUT_SECONDS", "30")),
            request_timeout_seconds=float(os.environ.get("AI_REQUEST_TIMEOUT_SECONDS", "90")),
            max_retries=int(os.environ.get("AI_MAX_RETRIES", "2")),
            chat_concurrency=int(os.environ.get("AI_CHAT_CONCURRENCY", "2")),
            embedding_concurrency=int(os.environ.get("AI_EMBEDDING_CONCURRENCY", "2")),
        )
