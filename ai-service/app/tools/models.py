"""工具响应结构校验；结构漂移时整次结果丢弃。"""

from dataclasses import dataclass

from pydantic import BaseModel, ConfigDict, Field


class ToolEnvelope(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    toolName: str
    facts: list[dict]
    truncated: bool
    asOf: str
    warnings: list[str] = Field(default_factory=list)


class Fact(BaseModel):
    model_config = ConfigDict(extra="allow", strict=True)

    toolName: str
    asOf: str


@dataclass(frozen=True)
class ToolCallResult:
    ok: bool
    facts: tuple[Fact, ...] = ()
    truncated: bool = False
    as_of: str | None = None
    warnings: tuple[str, ...] = ()
    error_code: str | None = None


def validate_envelope(tool_name: str, payload: dict) -> tuple[ToolEnvelope, list[Fact]]:
    """核对信封工具名与事实数量。"""
    if not isinstance(payload, dict):
        raise ValueError("INVALID_ENVELOPE")
    envelope = ToolEnvelope.model_validate(payload)
    if envelope.toolName != tool_name:
        raise ValueError("TOOL_NAME_MISMATCH")
    if len(envelope.facts) > 20:
        raise ValueError("TOO_MANY_FACTS")
    return envelope, [Fact.model_validate(fact) for fact in envelope.facts]
