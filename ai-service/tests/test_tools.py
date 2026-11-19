"""工具客户端成功、超时与结构漂移降级。"""

import asyncio

import httpx

from app.tools import ToolClient

AS_OF = "2026-11-19T00:00:00Z"


def envelope() -> dict:
    return {
        "toolName": "get_equipment_info",
        "facts": [{"toolName": "get_equipment_info", "asOf": AS_OF, "equipmentId": "12"}],
        "truncated": False,
        "asOf": AS_OF,
        "warnings": [],
    }


def run(operation):
    asyncio.run(operation())


def test_success_returns_facts():
    async def operation():
        def handler(request):
            assert request.headers["authorization"] == "Bearer token"
            return httpx.Response(200, json=envelope())

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://business") as gateway:
            result = await ToolClient(gateway).call("get_equipment_info", {"equipmentId": 12}, "token")
        assert result.ok is True
        assert result.facts[0].equipmentId == "12"

    run(operation)


def test_invalid_arguments_never_call_gateway():
    async def operation():
        async with httpx.AsyncClient(base_url="http://business") as gateway:
            result = await ToolClient(gateway).call("get_equipment_info", {"equipmentId": "12"}, "token")
        assert result.error_code == "TOOL_ARGUMENTS_INVALID"

    run(operation)


def test_timeout_degrades():
    async def operation():
        async def slow(request):
            await asyncio.sleep(0.2)
            return httpx.Response(200, json=envelope())

        async with httpx.AsyncClient(transport=httpx.MockTransport(slow), base_url="http://business") as gateway:
            result = await ToolClient(gateway, timeout=0.05).call("get_equipment_info", {"equipmentId": 12}, "token")
        assert result.error_code == "TOOL_TIMEOUT"

    run(operation)


def test_invalid_envelope_degrades():
    async def operation():
        def handler(request):
            return httpx.Response(200, json={"toolName": "get_equipment_info", "facts": []})

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://business") as gateway:
            result = await ToolClient(gateway).call("get_equipment_info", {"equipmentId": 12}, "token")
        assert result.error_code == "TOOL_INVALID_RESPONSE"

    run(operation)
