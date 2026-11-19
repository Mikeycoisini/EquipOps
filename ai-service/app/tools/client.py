"""工具客户端：固定路径、Bearer、3秒超时、失败降级且不记录令牌。"""

import asyncio
from typing import Any

import httpx

from app.tools.catalog import TOOLS, TOOL_NAME, validate_arguments
from app.tools.models import ToolCallResult, validate_envelope

MAX_RESPONSE_BYTES = 262144


class ToolClient:
    """复用应用回调网关客户端。"""

    def __init__(self, gateway: httpx.AsyncClient, *, timeout: float = 3.0,
                 max_response_bytes: int = MAX_RESPONSE_BYTES) -> None:
        if not isinstance(gateway, httpx.AsyncClient):
            raise TypeError("gateway must be httpx.AsyncClient")
        if timeout <= 0:
            raise ValueError("timeout must be positive")
        self._gateway = gateway
        self._timeout = timeout
        self._max_response_bytes = max_response_bytes

    async def call(self, tool_name: str, arguments: dict, token: str) -> ToolCallResult:
        """执行流程 1本地校验 2限时请求 3状态与体积 4结构校验。"""
        if not isinstance(tool_name, str) or not TOOL_NAME.fullmatch(tool_name) or tool_name not in TOOLS:
            return _degraded("TOOL_UNKNOWN")
        try:
            validated = validate_arguments(tool_name, arguments)
        except (ValueError, TypeError):
            return _degraded("TOOL_ARGUMENTS_INVALID")
        if not isinstance(token, str) or not token or len(token) > 8192:
            return _degraded("TOOL_CREDENTIAL_MISSING")
        try:
            async with asyncio.timeout(self._timeout):
                response = await self._gateway.post(
                    f"/internal/ai/tools/{tool_name}", json=validated,
                    headers={"Authorization": f"Bearer {token}"})
        except TimeoutError:
            return _degraded("TOOL_TIMEOUT")
        except httpx.HTTPError:
            return _degraded("TOOL_UNAVAILABLE")
        if response.status_code == 401:
            return _degraded("TOOL_CREDENTIAL_INVALID")
        if 400 <= response.status_code < 500:
            return _degraded("TOOL_REJECTED")
        if response.status_code >= 500:
            return _degraded("TOOL_UNAVAILABLE")
        if len(response.content) > self._max_response_bytes:
            return _degraded("TOOL_RESPONSE_TOO_LARGE")
        try:
            payload: Any = response.json()
            envelope, facts = validate_envelope(tool_name, payload)
        except (ValueError, TypeError):
            return _degraded("TOOL_INVALID_RESPONSE")
        return ToolCallResult(ok=True, facts=tuple(facts), truncated=envelope.truncated,
                              as_of=envelope.asOf, warnings=tuple(envelope.warnings))


def _degraded(code: str) -> ToolCallResult:
    return ToolCallResult(ok=False, warnings=(code,), error_code=code)
