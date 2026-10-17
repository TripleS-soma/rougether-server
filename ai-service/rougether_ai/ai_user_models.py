from datetime import datetime
import json
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, field_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class MemorySource(StrictModel):
    type: Literal["FEED_POST", "FEED_COMMENT", "CHAT", "MEMBERSHIP", "AI_JOB"]
    id: int = Field(gt=0)
    fingerprint: str = Field(pattern=r"^[a-f0-9]{64}$")


class MemoryFact(StrictModel):
    text: str = Field(min_length=1, max_length=2000)
    sources: list[MemorySource] = Field(min_length=1, max_length=64)
    episodes: list[Annotated[str, StringConstraints(pattern=r"^[a-f0-9-]{36}$")]] = Field(min_length=1, max_length=32)


class RecallRequest(StrictModel):
    aiUserId: int = Field(gt=0)
    query: str = Field(min_length=1, max_length=2000)
    counterpartUserId: int | None = Field(default=None,gt=0)


class InventoryRequest(StrictModel):
    aiUserId: int = Field(gt=0)
    cursor: str = Field(default="", max_length=36, pattern=r"^[a-f0-9-]*$")


class EpisodeRequest(StrictModel):
    aiUserId: int = Field(gt=0)
    jobId: int = Field(gt=0)
    body: str = Field(min_length=1, max_length=32000)
    occurredAt: datetime = Field(strict=False)
    sources: list[MemorySource] = Field(min_length=1, max_length=64)
    counterpartUserId: int | None = Field(default=None,gt=0)

    @field_validator("body")
    @classmethod
    def json_record_required(cls,value):
        if len(value.encode())>32000 or not isinstance(json.loads(value),dict):
            raise ValueError("기억 입력은 32KB 이내 JSON object여야 함")
        return value

    @field_validator("occurredAt")
    @classmethod
    def timezone_required(cls, value):
        if value.tzinfo is None:
            raise ValueError("시각에는 timezone이 필요함")
        return value


class DecisionRequest(StrictModel):
    aiUserId: int = Field(gt=0)
    state: dict
    facts: list[MemoryFact] = Field(default_factory=list, max_length=8)


class ForgetRequest(StrictModel):
    aiUserId: int = Field(gt=0)
    episodes: list[Annotated[str, StringConstraints(pattern=r"^[a-f0-9-]{36}$")]] = Field(min_length=1, max_length=32)
