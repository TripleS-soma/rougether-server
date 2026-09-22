from typing import Annotated, Literal
from pydantic import BaseModel, ConfigDict, Field, StringConstraints

Text = Annotated[str, StringConstraints(min_length=1, max_length=2048, strip_whitespace=True)]


class EmbeddingRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    model: str = Field(min_length=1, max_length=120)
    dimensions: int = Field(ge=1, le=3072)
    inputs: list[Text] = Field(min_length=1, max_length=128)


class CompletionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    model: str = Field(min_length=1, max_length=120)
    systemPrompt: str | None = Field(default=None, max_length=65536)
    userPrompt: str = Field(min_length=1, max_length=131072)
    maxTokens: int = Field(ge=1, le=4096)
    temperature: float | None = Field(default=None, ge=0, le=2, allow_inf_nan=False)
    jsonMode: bool = True
    reasoningEffort: Literal["none", "minimal", "low", "medium", "high", "xhigh"] | None = "low"
