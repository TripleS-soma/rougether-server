import os
from dataclasses import dataclass, field
from urllib.parse import urlparse


@dataclass(frozen=True)
class AiUserSettings:
    neo4j_uri: str
    neo4j_password: str = field(repr=False)
    anthropic_api_key: str = field(repr=False)
    neo4j_user: str = "neo4j"
    neo4j_database: str = "neo4j"
    model: str = "claude-haiku-5-5"
    human_model: str = "claude-sonnet-5-5"
    memory_model: str = "claude-haiku-5-5"
    timeout_seconds: float = 180

    def __post_init__(self):
        url = urlparse(self.neo4j_uri)
        if url.scheme not in {"bolt", "bolt+s", "neo4j+s"} or not url.hostname or url.username or url.password or url.query or url.fragment or url.path:
            raise ValueError("Neo4j URI는 자격 증명 없는 bolt 또는 TLS origin이어야 함")
        if not self.neo4j_password or not self.anthropic_api_key:
            raise ValueError("Neo4j와 Anthropic 자격 증명이 필요함")
        if not 1 <= self.timeout_seconds <= 180:
            raise ValueError("AI 사용자 작업 제한 시간 초과")

    @classmethod
    def from_env(cls):
        return cls(neo4j_uri=os.environ["AI_USER_NEO4J_URI"],
                   neo4j_password=os.environ["AI_USER_NEO4J_PASSWORD"],
                   anthropic_api_key=os.environ["ANTHROPIC_API_KEY"],
                   neo4j_user=os.getenv("AI_USER_NEO4J_USER", "neo4j"),
                   neo4j_database=os.getenv("AI_USER_NEO4J_DATABASE", "neo4j"),
                   model=os.getenv("AI_USER_MODEL", "claude-haiku-5-5"),
                   human_model=os.getenv("AI_USER_HUMAN_MODEL", "claude-sonnet-5-5"),
                   memory_model=os.getenv("AI_USER_MEMORY_MODEL", "claude-haiku-5-5"))
